package ru.serverastus.monitor.сеть

import ru.serverastus.monitor.данные.Безопасность
import ru.serverastus.monitor.данные.ДиагностикаСети
import ru.serverastus.monitor.данные.Метрики
import ru.serverastus.monitor.данные.Обслуживание
import ru.serverastus.monitor.данные.Подробности
import ru.serverastus.monitor.данные.Служба
import ru.serverastus.monitor.данные.л
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException

/** Проблема подключения к серверу или выполнения команды. */
class ОшибкаСвязи(
    сообщение: String,
    причина: Throwable? = null,
    /** Ответ пришёл обрезанным: команду можно переспросить (см. `снастойчивымЧтением`). */
    val обрезано: Boolean = false,
) : Exception(сообщение, причина)

/** Куда и как подключаться — вырезано из настроек приложения. */
data class ПараметрыСервера(
    val хост: String = "",
    val порт: Int = 22,
    val пользователь: String = "",
    val пароль: String = "",
    val файлКлюча: String = "",
    val таймаутСекунд: Int = 20,
    val дискПуть: String = "/",
) {
    val адрес: String get() = "$хост:$порт"
}

// Быстрые метрики — опрашиваются часто: ЦП, память, диск, сеть, аптайм.
// Одна поездка на сервер вместо нескольких: команды печатают метку-разделитель.
private const val СНИМОК_БЫСТРО = """export LC_ALL=C
echo '{м}'
cat /proc/stat
echo '{м}'
sleep 0.4
cat /proc/stat
echo '{м}'
cat /proc/loadavg
echo '{м}'
cat /proc/meminfo
echo '{м}'
cat /proc/uptime
echo '{м}'
nproc
echo '{м}'
df -B1 -P 2>/dev/null
echo '{м}'
cat /proc/net/dev
echo '{м}'
"""

// Медленно меняющиеся данные: список процессов, версия ядра/ОС, температура.
private const val СНИМОК_ПОДРОБНО = """export LC_ALL=C
echo '{м}'
ps -eo pcpu=,pmem=,comm= --sort=-pcpu 2>/dev/null | head -n 8
echo '{м}'
uname -r
hostname
echo '{м}'
cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null
echo '{м}'
( . /etc/os-release 2>/dev/null; printf '%s\n' "${'$'}PRETTY_NAME" )
echo '{м}'
"""

// Безопасность: состояние fail2ban (jail'ы, кто забанен), последние Ban/Unban
// из его журнала и текущие ручные блокировки ufw. Требует sudo: пароль уходит
// в stdin первого `sudo -S -p '' true`, дальше используется `sudo -n` — sudo
// кэширует авторизацию в пределах одного процесса.
private const val СНИМОК_БЕЗОПАСНОСТИ = """export LC_ALL=C
sudo -S -p '' true
echo '{м}'
sudo -n bash -c 'for j in ${'$'}(fail2ban-client status 2>/dev/null | sed -n "s/.*Jail list:[[:space:]]*//p" | tr "," " "); do echo "==${'$'}j=="; fail2ban-client status "${'$'}j" 2>/dev/null; done'
echo '{м}'
sudo -n tail -n 500 /var/log/fail2ban.log 2>/dev/null | grep -E "NOTICE.*(Ban|Unban) "
echo '{м}'
sudo -n ufw status numbered 2>&1
echo '{м}'
"""

// Диагностика TCP и UDP: счётчики ядра, состояния сокетов и текущие параметры
// сети. Все файлы читаются без sudo, `sysctl -e` не падает на ключах, которых
// в этом ядре нет (например без netfilter-модуля). Последняя часть —
// `/proc/net/softnet_stat` — отличает настоящее переполнение программной
// очереди приёма (net.core.netdev_max_backlog) от потерь на интерфейсе,
// вызванных чем-то другим (файрвол, мультикаст без подписчика): без неё
// совет «увеличьте netdev_max_backlog» иногда предлагался, даже когда
// значение уже стояло по максимуму. Последняя часть — состояние линков из
// `/sys/class/net`: в `/proc/net/dev` его нет, а именно оно отличает живую
// линию от оборванной и показывает скорость с режимом дуплекса. Части с
// состоянием линка может не быть (старая версия на сервере), поэтому она
// читается по индексу и без неё разбор работает как раньше. Питоновский
// двойник — те же части в том же порядке, см. `_СНИМОК_СЕТИ` в `ssh_linux.py`.
private const val СНИМОК_СЕТЬ = """export LC_ALL=C
echo '{м}'
cat /proc/net/snmp
echo '{м}'
cat /proc/net/netstat
echo '{м}'
cat /proc/net/sockstat 2>/dev/null; cat /proc/net/sockstat6 2>/dev/null
echo '{м}'
ss -s 2>/dev/null
echo '{м}'
ss -ltnuH 2>/dev/null | wc -l
echo '{м}'
cat /proc/net/dev
echo '{м}'
cat /proc/sys/net/netfilter/nf_conntrack_count 2>/dev/null
cat /proc/sys/net/netfilter/nf_conntrack_max 2>/dev/null
echo '{м}'
sysctl -e net.ipv4.tcp_congestion_control net.ipv4.tcp_available_congestion_control net.core.default_qdisc net.core.somaxconn net.ipv4.tcp_max_syn_backlog net.ipv4.tcp_fin_timeout net.ipv4.tcp_tw_reuse net.ipv4.tcp_max_tw_buckets net.ipv4.ip_local_port_range net.core.netdev_max_backlog net.core.rmem_max net.core.wmem_max net.core.rmem_default net.core.wmem_default net.ipv4.tcp_rmem net.ipv4.tcp_wmem net.ipv4.tcp_mem net.ipv4.udp_mem net.ipv4.tcp_syncookies net.ipv4.tcp_slow_start_after_idle net.ipv4.tcp_fastopen net.ipv4.tcp_mtu_probing net.ipv4.tcp_keepalive_time net.ipv4.tcp_retries2 net.ipv4.tcp_syn_retries net.ipv4.tcp_synack_retries net.netfilter.nf_conntrack_max net.netfilter.nf_conntrack_tcp_timeout_established 2>/dev/null
echo '{м}'
cat /proc/net/softnet_stat
echo '{м}'
for i in /sys/class/net/*; do n=${'$'}{i##*/}; printf '%s %s %s %s %s\n' "${'$'}{n}" "${'$'}(cat ${'$'}i/operstate 2>/dev/null || echo -)" "${'$'}(cat ${'$'}i/carrier 2>/dev/null || echo -)" "${'$'}(cat ${'$'}i/speed 2>/dev/null || echo -)" "${'$'}(cat ${'$'}i/duplex 2>/dev/null || echo -)"; done
echo '{м}'
"""

// Аудит обслуживания: обновления пакетов, сбойные службы, журналы, время и
// накопители. Ни одна команда не требует sudo, поэтому снимок снимается обычным
// пользователем; здоровье дисков (smartctl) запрашивается отдельно.
private const val СНИМОК_ОБСЛУЖИВАНИЕ = """export LC_ALL=C
echo '{м}'
cat /proc/uptime
echo '{м}'
cat /var/run/reboot-required 2>/dev/null
echo '{м}'
uname -r
echo '{м}'
dpkg-query -f '${'$'}{binary:Package}\t${'$'}{Version}\n' -W 'linux-image-*' 2>/dev/null | sort -V | tail -n 6
echo '{м}'
apt-get -s -o Debug::NoLocking=1 upgrade 2>/dev/null | grep -c '^Inst'
echo '{м}'
apt list --upgradable 2>/dev/null | tail -n +2 | wc -l
echo '{м}'
apt list --upgradable 2>/dev/null | grep -c security
echo '{м}'
apt-get -s autoremove 2>/dev/null | grep -c '^Remv'
echo '{м}'
systemctl --failed --no-legend --no-pager --plain 2>/dev/null | head -n 20
echo '{м}'
du -c -sb /var/log/journal /run/log/journal 2>/dev/null | tail -n 1 | cut -f1
echo '{м}'
du -sb /var/log 2>/dev/null | cut -f1
echo '{м}'
du -sm /var/log/* 2>/dev/null | sort -rn | head -n 6
echo '{м}'
timedatectl show -p NTPSynchronized -p Timezone 2>/dev/null
echo '{м}'
for s in systemd-timesyncd chrony ntp openntpd; do printf '%s=%s\n' "${'$'}s" "${'$'}(systemctl is-active ${'$'}s 2>/dev/null)"; done
echo '{м}'
systemd-analyze 2>/dev/null | head -n 1
echo '{м}'
df -B1 -P <ДИСК> 2>/dev/null | tail -n +2
echo '{м}'
grep -E '^(SwapTotal|SwapFree|MemTotal|MemAvailable):' /proc/meminfo
echo '{м}'
dpkg-query -f '${'$'}{binary:Package}\n' -W 2>/dev/null | wc -l
echo '{м}'
systemctl list-unit-files --state=enabled --no-legend --no-pager 2>/dev/null | wc -l
echo '{м}'
systemctl is-enabled unattended-upgrades 2>&1 | head -n 2; systemctl is-enabled logrotate.timer 2>&1; systemctl is-active logrotate.timer 2>&1
echo '{м}'
lsblk -dn -e 7 -o NAME,SIZE,MODEL 2>/dev/null | head -n 8
echo '{м}'
findmnt -n -l -o SOURCE 2>/dev/null | grep '^/dev/' | head -n 20
echo '{м}'
grep -Ev '^#|^${'$'}' /etc/fstab 2>/dev/null | head -n 20
echo '{м}'
command -v smartctl 2>/dev/null || true
echo '{м}'
echo 'конец снимка'
"""

// Здоровье дисков: smartctl требует root, поэтому команда целиком уходит под
// sudo и печатает по строке-метке на каждое найденное устройство.
private const val КОМАНДА_SMART = "bash -c 'for d in /dev/sd? /dev/vd? /dev/nvme?n1; do " +
    "[ -b \"\$d\" ] || continue; echo \"${МЕТКА_ДИСКА}\$d\"; " +
    "smartctl -H \"\$d\" 2>/dev/null | sed -n -e \"s/^SMART overall-health self-assessment test result: /итог /p\" " +
    "-e \"s/^SMART Health Status: /итог /p\"; " +
    "smartctl -A \"\$d\" 2>/dev/null | grep -E \"^ *(5|9|194|197) +[A-Za-z]\"; done'"

/**
 * Сколько раз переспрашивать сервер, если ответ пришёл обрезанным, и пауза
 * между попытками: при обрыве канала JSch отдаёт укороченный вывод.
 */
private const val ПОПЫТОК_ЧТЕНИЯ = 3
private const val ПАУЗА_ПЕРЕД_ПОВТОРОМ_МС = 400L

/**
 * Подключение к Linux-серверу и сбор его показателей по SSH.
 *
 * Состав команд и разбор ответов повторяют настольное приложение
 * (`server_monitor/ssh_linux.py`) — сервер обслуживается тем же протоколом.
 */
class LinuxServer(
    private val параметры: ПараметрыСервера,
    private val журнал: (String) -> Unit = {},
) {
    private var jsch: JSch? = null
    private var сессия: Session? = null
    private var счётчикиСети: СчётчикиСети? = null
    private var прежняяДиагностика: ДиагностикаСети? = null

    var ядер: Int? = null
        private set
    var сведения: MutableMap<String, Any?> = mutableMapOf()
        private set

    val подключено: Boolean get() = сессия?.isConnected == true

    private fun записать(сообщение: String) = журнал(сообщение)

    /** Закрывает соединение. Следующий запрос подключится заново. */
    fun закрыть() {
        try {
            сессия?.disconnect()
        } catch (_: Exception) {
            // закрытие не должно мешать работе
        }
        сессия = null
        счётчикиСети = null
        прежняяДиагностика = null
    }

    private fun подключить(): Session {
        сессия?.let { if (it.isConnected) return it }
        if (параметры.хост.isBlank()) throw ОшибкаСвязи(л("В настройках не указан адрес сервера"))
        if (параметры.пользователь.isBlank()) throw ОшибкаСвязи(л("В настройках не указан пользователь сервера"))

        val клиент = JSch()
        // Ключ сервера не сверяем с known_hosts (как AutoAddPolicy в paramiko),
        // но фиксируем список алгоритмов — у сервера включены все три ключа.
        JSch.setConfig("StrictHostKeyChecking", "no")
        JSch.setConfig(
            "server_host_key",
            "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-ed25519",
        )
        if (параметры.файлКлюча.isNotBlank()) {
            try {
                клиент.addIdentity(параметры.файлКлюча)
            } catch (ошибка: JSchException) {
                throw ОшибкаСвязи(л("Не удалось прочитать файл ключа «{1}» — {2}", параметры.файлКлюча, ошибка.message))
            }
        }

        val новая = try {
            клиент.getSession(параметры.пользователь, параметры.хост, параметры.порт)
        } catch (ошибка: JSchException) {
            throw ОшибкаСвязи(л("Не удалось подключиться к {1} — {2}", параметры.адрес, ошибка.message), ошибка)
        }
        if (параметры.пароль.isNotEmpty()) новая.setPassword(параметры.пароль)
        новая.setConfig("StrictHostKeyChecking", "no")
        новая.serverAliveInterval = 15000
        новая.serverAliveCountMax = 3
        новая.timeout = параметры.таймаутСекунд * 1000
        try {
            новая.connect(параметры.таймаутСекунд * 1000)
        } catch (ошибка: JSchException) {
            val текст = ошибка.message ?: ""
            val причина = when {
                текст.contains("Auth fail", true) || текст.contains("auth", true) ->
                    л("Сервер отклонил логин или пароль — проверьте настройки подключения")
                текст.contains("HostKey", true) ->
                    л("Не совпадает ключ сервера — возможно, адрес подменён")
                else -> л("Не удалось подключиться к {1} — {2}", параметры.адрес, текст)
            }
            throw ОшибкаСвязи(причина, ошибка)
        }
        jsch = клиент
        сессия = новая
        return новая
    }

    /** Выполняет команду и возвращает (код возврата, вывод, ошибки). */
    private fun выполнитьВКанале(
        команда: String,
        таймаут: Int,
        парольВStdin: String? = null,
    ): Triple<Int, String, String> {
        val действующая = подключить()
        val канал = try {
            действующая.openChannel("exec") as ChannelExec
        } catch (ошибка: JSchException) {
            закрыть()
            throw ОшибкаСвязи(л("Связь с сервером потеряна — {1}", ошибка.message), ошибка)
        }

        val буферОшибок = StringBuilder()
        try {
            канал.setCommand(команда)
            канал.setInputStream(null)
            // Потоки берём до connect(): JSch наливает вывод только в уже
            // полученные объекты потоков, иначе первые пакеты молча теряются
            // (проверено на реальном сервере — пропадала часть /proc/stat).
            val потокВывода = канал.inputStream
            val потокОшибокКанала = канал.errStream

            val потокОшибок = Thread {
                try {
                    буферОшибок.append(потокОшибокКанала.readBytes().toString(Charsets.UTF_8))
                } catch (_: Exception) {
                    // поток ошибок может быть уже закрыт
                }
            }
            потокОшибок.isDaemon = true
            потокОшибок.start()

            канал.connect(таймаут * 1000)

            if (парольВStdin != null && парольВStdin.isNotEmpty()) {
                // stdin канала — это ввод команды: sudo -S читает пароль оттуда.
                // Закрывать поток не нужно: sudo берёт одну строку и работает дальше.
                val ввод = канал.outputStream
                ввод.write((парольВStdin + "\n").toByteArray(Charsets.UTF_8))
                ввод.flush()
            }

            val вывод = потокВывода.readBytes().toString(Charsets.UTF_8)

            var ожидание = 0
            while (!канал.isClosed && ожидание < таймаут * 1000) {
                Thread.sleep(20)
                ожидание += 20
            }
            val код = if (канал.isClosed) канал.exitStatus else -1
            потокОшибок.join(2000)
            return Triple(код, вывод, буферОшибок.toString())
        } catch (ошибка: IOException) {
            закрыть()
            throw ОшибкаСвязи(л("Связь с сервером потеряна — {1}", ошибка.message), ошибка)
        } catch (ошибка: JSchException) {
            закрыть()
            throw ОшибкаСвязи(л("Связь с сервером потеряна — {1}", ошибка.message), ошибка)
        } finally {
            try {
                канал.disconnect()
            } catch (_: Exception) {
                // канал мог быть уже закрыт
            }
        }
    }

    /** Выполняет команду и возвращает (вывод, ошибки). */
    fun выполнить(команда: String, таймаут: Int = параметры.таймаутСекунд): Pair<String, String> {
        val (_, вывод, ошибки) = выполнитьВКанале(команда, таймаут)
        return вывод to ошибки.trim()
    }

    /** Выполняет одну команду через `sudo -S`, передавая пароль в stdin. */
    private fun выполнитьСПаролем(команда: String, таймаут: Int = 90): Triple<Int, String, String> =
        выполнитьВКанале("export LC_ALL=C; sudo -S -p '' $команда", таймаут, параметры.пароль)

    /** Быстрая проверка доступа: возвращает (получилось, сообщение). */
    fun проверитьПодключение(): Pair<Boolean, String> {
        val команда = "export LC_ALL=C; hostname; id -un; uname -r; " +
            "( . /etc/os-release 2>/dev/null; echo ${'$'}PRETTY_NAME )"
        val вывод = try {
            выполнить(команда).first
        } catch (ошибка: ОшибкаСвязи) {
            return false to (ошибка.message ?: л("Не удалось подключиться"))
        }
        val строки = вывод.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val имя = строки.getOrNull(0) ?: "?"
        val пользователь = строки.getOrNull(1) ?: параметры.пользователь
        val ядро = строки.getOrNull(2) ?: ""
        val ос = строки.getOrNull(3) ?: ""
        val сводка = buildString {
            append(л("Связь есть: хост «{1}», пользователь «{2}»", имя, пользователь))
            if (ос.isNotEmpty()) append(л(", ОС: {1}", ос))
            if (ядро.isNotEmpty()) append(л(", ядро {1}", ядро))
        }
        return true to сводка
    }

    // --- автопоиск целей для мастера настройки ---------------------------

    /** Домены из конфигурации nginx и Apache — заготовки для списка сайтов. */
    fun найтиДомены(): List<String> {
        // Комментарии (всё от '#' до конца строки) вырезаются через sed ДО поиска
        // server_name/ServerName — иначе строки вида "# ...(or add a second server
        // block);" совпадают с 'server_name[[:space:]]+[^;]+' и каждое слово в них
        // принимается за отдельный «домен». Директива после вырезания комментария
        // дополнительно привязывается к началу строки (^[[:space:]]*), чтобы не
        // подхватывать обрывки конфигурации, где ключевое слово — не первое.
        // Сам отбор адресов — в чистой функции `доменыИзServerName` (`Разбор.kt`).
        val команда = """export LC_ALL=C
find /etc/nginx/sites-enabled /etc/nginx/sites-available /etc/nginx/conf.d /etc/nginx/nginx.conf -type f 2>/dev/null | xargs -r sed 's/#.*//' 2>/dev/null | grep -ohE '^[[:space:]]*server_name[[:space:]]+[^;]+'
find /etc/apache2 /etc/httpd -type f 2>/dev/null | xargs -r sed 's/#.*//' 2>/dev/null | grep -ohE '^[[:space:]]*ServerName[[:space:]]+[^[:space:]]+'
"""
        return доменыИзServerName(выполнить(команда).first)
    }

    /** Слушающие TCP-порты, доступные извне — заготовки для списка портов. */
    fun найтиСлушающиеПорты(): List<Int> {
        val вывод = выполнить("export LC_ALL=C; ss -ltnH 2>/dev/null || netstat -ltn 2>/dev/null").first
        val порты = LinkedHashSet<Int>()
        for (строка in вывод.split("\n")) {
            val адрес = строка.trim().split(Regex("\\s+"))
                .firstOrNull { кусок -> кусок.substringAfterLast(':', "").toIntOrNull() != null }
                ?: continue
            if (!доступенИзвне(адрес)) continue
            адрес.substringAfterLast(':').toIntOrNull()?.let { порты.add(it) }
        }
        return порты.sorted()
    }

    private fun доступенИзвне(адрес: String): Boolean {
        val узел = адрес.substringBeforeLast(':', "")
        return узел.isEmpty() || узел == "*" || узел == "0.0.0.0" || узел == "::" || узел == "[::]"
    }

    /** Работающие сейчас службы systemd: пары (имя, описание). */
    fun найтиСлужбы(): List<Pair<String, String>> {
        val команда = "export LC_ALL=C; systemctl list-units --type=service --state=running " +
            "--no-legend --no-pager 2>/dev/null"
        return службыИзВывода(выполнить(команда).first)
    }

    /**
     * Повторяет чтение, если сервер ответил обрезанно: при обрыве канала JSch
     * отдаёт укороченный вывод, и разбор его отбраковывает. Переспросить с новым
     * соединением дешевле, чем показать пользователю ошибку связи.
     */
    private fun <Т> снастойчивымЧтением(чтение: () -> Т): Т {
        for (попытка in 1..ПОПЫТОК_ЧТЕНИЯ) {
            try {
                return чтение()
            } catch (ошибка: ОшибкаСвязи) {
                val обрезано = ошибка.обрезано
                if (!обрезано || попытка == ПОПЫТОК_ЧТЕНИЯ) throw ошибка
                записать(л("Сервер вернул неполный ответ — повторяю запрос (попытка {1} из {2})", попытка + 1, ПОПЫТОК_ЧТЕНИЯ))
                закрыть()
                Thread.sleep(ПАУЗА_ПЕРЕД_ПОВТОРОМ_МС)
            }
        }
        throw ОшибкаСвязи(л("Сервер не ответил"))
    }

    /** Снимает быстро меняющиеся показатели: ЦП, ОЗУ, диск, сеть, аптайм. */
    fun снятьБыстро(): Метрики = снастойчивымЧтением { снятьБыстроЗаРаз() }

    private fun снятьБыстроЗаРаз(): Метрики {
        val начало = System.currentTimeMillis()
        val вывод = выполнить(СНИМОК_БЫСТРО.replace("{м}", МЕТКА), параметры.таймаутСекунд + 5).first
        val длительность = System.currentTimeMillis() - начало

        val сейчас = System.currentTimeMillis() / 1000.0
        val метрики = собратьБыстро(разделить(вывод), параметры.дискПуть, счётчикиСети, сейчас, длительность)
        счётчикиСети = СчётчикиСети(сейчас, метрики.сетьПолучено, метрики.сетьОтправлено)
        ядер = метрики.ядер
        return метрики
    }

    /** Снимает медленно меняющиеся данные: топ-процессы, ядро, ОС, температуру. */
    fun снятьПодробно(): Подробности = снастойчивымЧтением { снятьПодробноЗаРаз() }

    private fun снятьПодробноЗаРаз(): Подробности {
        val вывод = выполнить(СНИМОК_ПОДРОБНО.replace("{м}", МЕТКА), параметры.таймаутСекунд + 5).first
        val подробности = собратьПодробно(разделить(вывод), параметры.хост)
        сведения["хост"] = подробности.хост
        сведения["ос"] = подробности.ос
        сведения["ядро"] = подробности.ядро
        return подробности
    }

    /** Снимает все показатели за один вызов: быстрые метрики + подробности. */
    fun снятьВсё(): Метрики {
        val метрики = снятьБыстро()
        val подробности = снятьПодробно()
        return метрики.copy(
            топПроцессов = подробности.топПроцессов,
            хост = подробности.хост,
            ос = подробности.ос,
            ядро = подробности.ядро,
            температура = подробности.температура,
        )
    }

    // --- диагностика сети: TCP и UDP -------------------------------------

    /**
     * Снимает счётчики TCP/UDP, состояния сокетов, таблицу соединений и
     * параметры ядра. Скорость изменения счётчиков считается по разнице с
     * предыдущим снимком — он хранится здесь же и обнуляется при разрыве связи.
     */
    fun собратьСеть(): ДиагностикаСети = снастойчивымЧтением {
        val вывод = выполнить(СНИМОК_СЕТЬ.replace("{м}", МЕТКА), параметры.таймаутСекунд + 5).first
        val сейчас = System.currentTimeMillis() / 1000
        val снимок = добавитьТемп(собратьДиагностикуСети(разделить(вывод), сейчас), прежняяДиагностика)
        прежняяДиагностика = снимок
        снимок
    }

    // --- обслуживание сервера --------------------------------------------

    /**
     * Проводит аудит обслуживания: обновления пакетов, сбойные службы, журналы,
     * время загрузки и накопители. Здоровье дисков читается вторым вызовом и
     * только когда на сервере есть smartctl и задан пароль для sudo.
     */
    fun собратьОбслуживание(): Обслуживание = снастойчивымЧтением {
        val команда = СНИМОК_ОБСЛУЖИВАНИЕ
            .replace("{м}", МЕТКА)
            .replace("<ДИСК>", оболочка(параметры.дискПуть.ifBlank { "/" }))
        val вывод = выполнить(команда, параметры.таймаутСекунд + 20).first
        val снимок = собратьОбслуживание(разделить(вывод), System.currentTimeMillis() / 1000)
        if (!снимок.smartДоступен || параметры.пароль.isBlank()) return@снастойчивымЧтением снимок
        val здоровье = try {
            разобратьЗдоровьеДисков(выполнитьСПаролем(КОМАНДА_SMART, 60).second)
        } catch (ошибка: ОшибкаСвязи) {
            записать(л("Здоровье дисков не прочитано: {1}", ошибка.message))
            emptyList()
        }
        снимок.copy(дискиЗдоровье = здоровье)
    }

    // --- службы ----------------------------------------------------------

    /** Возвращает состояние перечисленных служб systemd. */
    fun службы(units: List<String>): List<Служба> {
        val список = units.map { it.trim() }.filter { it.isNotEmpty() }
        if (список.isEmpty()) return emptyList()
        val имена = список.joinToString(" ") { оболочка(it) }
        val команда = buildString {
            append("export LC_ALL=C\n")
            append("for u in $имена; do\n")
            append("  state=$(systemctl is-active \"\$u\" 2>/dev/null || true)\n")
            append("  load=$(systemctl show -p LoadState --value \"\$u\" 2>/dev/null || true)\n")
            append("  desc=$(systemctl show -p Description --value \"\$u\" 2>/dev/null || true)\n")
            append("  since=$(systemctl show -p ActiveEnterTimestamp --value \"\$u\" 2>/dev/null || true)\n")
            append("  printf '%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$u\" \"\$state\" \"\$load\" \"\$desc\" \"\$since\"\n")
            append("done")
        }
        return собратьСлужбы(выполнить(команда, параметры.таймаутСекунд).first)
    }

    // Глаголы для сообщений — по одному на каждое действие systemctl.
    private fun глаголы(действие: String): Pair<String, String> = when (действие) {
        "start" -> л("запустить") to л("запущена")
        "stop" -> л("остановить") to л("остановлена")
        else -> л("перезапустить") to л("перезапущена")
    }

    private fun systemctl(unit: String, действие: String): Pair<Boolean, String> {
        if (unit.isBlank()) return false to л("Не указано имя службы")
        val (инфинитив, причастие) = глаголы(действие)
        val (код, вывод, ошибки) = try {
            выполнитьСПаролем("systemctl $действие ${оболочка(unit)}")
        } catch (ошибка: ОшибкаСвязи) {
            return false to (ошибка.message ?: л("Связь с сервером потеряна"))
        }
        if (код == 0) return true to л("Служба «{1}» {2}", unit, причастие)
        var причина = ошибки.ifBlank { вывод.ifBlank { л("код возврата {1}", код) } }
        val нижний = причина.lowercase()
        if (нижний.contains("password") || нижний.contains("sudo")) {
            причина += л(" (пользователю нужно право на sudo)")
        }
        return false to л("Не удалось {1} службу «{2}»: {3}", инфинитив, unit, причина)
    }

    fun запуститьСлужбу(unit: String): Pair<Boolean, String> = systemctl(unit, "start")

    fun остановитьСлужбу(unit: String): Pair<Boolean, String> = systemctl(unit, "stop")

    fun перезапуститьСлужбу(unit: String): Pair<Boolean, String> = systemctl(unit, "restart")

    // --- безопасность: fail2ban + блокировка IP через ufw -----------------

    /** Снимок состояния fail2ban и текущих ручных блокировок ufw. Требует sudo. */
    fun собратьБезопасность(): Безопасность = снастойчивымЧтением {
        val вывод = выполнитьВКанале(
            СНИМОК_БЕЗОПАСНОСТИ.replace("{м}", МЕТКА),
            параметры.таймаутСекунд + 10,
            параметры.пароль,
        ).second
        собратьБезопасность(разделить(вывод), System.currentTimeMillis() / 1000)
    }

    /**
     * Блокирует IP через ufw — тем же способом, каким пользуется fail2ban на
     * сервере (banaction = ufw). Правило вставляется ПЕРВЫМ: остальные правила
     * ufw — ALLOW с любого адреса на конкретные порты, и правило DENY в конце
     * никогда бы не сработало.
     */
    fun заблокироватьIp(ip: String): Pair<Boolean, String> {
        val адрес = ip.trim()
        if (!корректныйIp(адрес)) return false to л("«{1}» не похоже на IP-адрес", адрес)
        val (код, вывод, ошибки) = try {
            выполнитьСПаролем("ufw insert 1 deny from ${оболочка(адрес)} to any")
        } catch (ошибка: ОшибкаСвязи) {
            return false to (ошибка.message ?: л("Связь с сервером потеряна"))
        }
        if (код == 0) return true to л("IP {1} заблокирован", адрес)
        return false to л("Не удалось заблокировать {1}: {2}", адрес, ошибки.ifBlank { вывод.ifBlank { л("код возврата {1}", код) } })
    }

    /**
     * Снимает ручную блокировку ufw (автоматические баны fail2ban не трогает —
     * те снимаются сами по истечении bantime).
     */
    fun разблокироватьIp(ip: String): Pair<Boolean, String> {
        val адрес = ip.trim()
        if (!корректныйIp(адрес)) return false to л("«{1}» не похоже на IP-адрес", адрес)
        val (код, вывод, ошибки) = try {
            выполнитьСПаролем("ufw delete deny from ${оболочка(адрес)} to any")
        } catch (ошибка: ОшибкаСвязи) {
            return false to (ошибка.message ?: л("Связь с сервером потеряна"))
        }
        if (код == 0) return true to л("Блокировка {1} снята", адрес)
        return false to л("Не удалось снять блокировку {1}: {2}", адрес, ошибки.ifBlank { вывод.ifBlank { л("код возврата {1}", код) } })
    }
}

/** Экранирует значение для оболочки — как shlex.quote. */
internal fun оболочка(значение: String): String =
    if (Regex("^[A-Za-z0-9_@%+=:,./-]+$").matches(значение)) значение
    else "'" + значение.replace("'", "'\\''") + "'"
