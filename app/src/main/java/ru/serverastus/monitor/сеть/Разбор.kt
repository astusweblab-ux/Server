package ru.serverastus.monitor.сеть

import ru.serverastus.monitor.данные.Безопасность
import ru.serverastus.monitor.данные.ЗдоровьеДиска
import ru.serverastus.monitor.данные.КрупныйЛог
import ru.serverastus.monitor.данные.Накопитель
import ru.serverastus.monitor.данные.НакопительАудита
import ru.serverastus.monitor.данные.Обслуживание
import ru.serverastus.monitor.данные.ПравилоUfw
import ru.serverastus.monitor.данные.Процесс
import ru.serverastus.monitor.данные.Подробности
import ru.serverastus.monitor.данные.Служба
import ru.serverastus.monitor.данные.СобытиеFail2ban
import ru.serverastus.monitor.данные.Метрики
import ru.serverastus.monitor.данные.Jail
import ru.serverastus.monitor.данные.СОСТОЯНИЯ_СЛУЖБ
import ru.serverastus.monitor.данные.л
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Разбор ответов сервера. Здесь только чистые функции — ни сети, ни Android,
 * поэтому разбор можно проверять тестами на заранее снятых ответах сервера.
 * Логика повторяет `server_monitor/ssh_linux.py` из настольной версии.
 */

const val МЕТКА = "@@МОНИТОР@@"

private val РАЗДЕЛИТЕЛЬ = Regex("\\s*" + Regex.escape(МЕТКА) + "\\s*")
private val ЭКРАНИРОВАНИЕ = Regex("\\\\x([0-9a-fA-F]{2})")
private val СТРОКА_JAIL = Regex("^==(.+)==$")
private val СТРОКА_СОБЫТИЯ = Regex(
    "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}),\\d+\\s+\\S+\\s+\\[\\d+\\]:\\s+NOTICE\\s+\\[([^\\]]+)\\]\\s+(Ban|Unban)\\s+(\\S+)"
)
private val СТРОКА_НОМЕРА_UFW = Regex("^\\[\\s*(\\d+)\\]\\s+(.*)$")
private val РАЗДЕЛИТЕЛЬ_ПОЛЕЙ = Regex("\\s{2,}")
private val ШЕСТНАДЦАТЕРИЧНЫЙ_БЛОК = Regex("^[0-9a-fA-F]{1,4}$")

private val ФОРМАТ_ВРЕМЕНИ_F2B = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Поля jail'а fail2ban: что искать в строке и куда положить число. */
private val ПОЛЯ_JAIL = listOf(
    "Currently failed" to 0,
    "Total failed" to 1,
    "Currently banned" to 2,
    "Total banned" to 3,
)

/** Счётчики трафика между опросами — нужны для скорости приёма/отдачи. */
data class СчётчикиСети(val время: Double, val принято: Long, val отправлено: Long)

/** Разбивает вывод команды на части по метке-разделителю. */
fun разделить(текст: String?): List<String> {
    val полный = текст ?: ""
    val части = mutableListOf<String>()
    var начало = 0
    for (совпадение in РАЗДЕЛИТЕЛЬ.findAll(полный)) {
        части.add(полный.substring(начало, совпадение.range.first).trim())
        начало = совпадение.range.last + 1
    }
    части.add(полный.substring(начало).trim())
    while (части.isNotEmpty() && части.first().isEmpty()) части.removeAt(0)
    while (части.isNotEmpty() && части.last().isEmpty()) части.removeAt(части.size - 1)
    return части
}

/** Число из строки; при неудаче — значение по умолчанию (как `_число`). */
fun число(значение: Any?, поУмолчанию: Double? = null): Double? {
    val текст = значение?.toString()?.replace(",", ".") ?: return поУмолчанию
    return текст.toDoubleOrNull() ?: поУмолчанию
}

fun целое(значение: Any?, поУмолчанию: Int? = null): Int? {
    val текст = значение?.toString()?.trim() ?: return поУмолчанию
    return текст.toIntOrNull() ?: поУмолчанию
}

/** Округление как в Python: до заданного числа знаков, половина — к чётному. */
fun округлить(значение: Double, знаков: Int): Double {
    if (значение.isNaN() || значение.isInfinite()) return значение
    return BigDecimal(значение.toString()).setScale(знаков, RoundingMode.HALF_EVEN).toDouble()
}

/** Раскодирует `\xNN`, которыми systemd экранирует непечатаемые символы. */
fun разэкранировать(текст: String?): String {
    val строка = текст ?: ""
    if (!строка.contains("\\x")) return строка
    return try {
        val байты = ЭКРАНИРОВАНИЕ.replace(строка) { совпадение ->
            String(byteArrayOf(совпадение.groupValues[1].toInt(16).toByte()), Charsets.ISO_8859_1)
        }
        String(байты.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
    } catch (_: Exception) {
        строка
    }
}

/** Строка похожа на настоящий IPv4/IPv6-адрес? Сети и маски не считаются. */
fun корректныйIp(текст: String?): Boolean {
    val строка = (текст ?: "").trim()
    if (строка.isEmpty()) return false
    return этоIPv4(строка) || этоIPv6(строка)
}

private fun этоIPv4(текст: String): Boolean {
    val части = текст.split(".")
    if (части.size != 4) return false
    return части.all { часть ->
        часть.isNotEmpty() && часть.length <= 3 && часть.all { it.isDigit() } &&
            (часть.toIntOrNull() ?: -1) in 0..255
    }
}

private fun этоIPv6(текст: String): Boolean {
    if (!текст.contains(':')) return false
    if (Regex("::").findAll(текст).count() > 1) return false

    fun блоки(часть: String): List<String>? {
        if (часть.isEmpty()) return emptyList()
        val список = часть.split(":")
        return if (список.any { it.isEmpty() }) null else список
    }

    val разделённые = текст.split("::")
    if (разделённые.size > 2) return false
    val левые = блоки(разделённые[0]) ?: return false
    val правые = if (разделённые.size == 2) (блоки(разделённые[1]) ?: return false) else emptyList()
    val все = левые + правые

    var счёт = 0
    for ((индекс, блок) in все.withIndex()) {
        if (блок.contains('.')) {
            if (индекс != все.size - 1 || !этоIPv4(блок)) return false
            счёт += 2
        } else {
            if (!ШЕСТНАДЦАТЕРИЧНЫЙ_БЛОК.matches(блок)) return false
            счёт += 1
        }
    }
    return if (разделённые.size == 2) счёт < 8 else счёт == 8
}

// --- быстрые метрики --------------------------------------------------------

/** Возвращает (простой, всего) из строки «cpu ...» файла /proc/stat. */
fun времяЦп(текст: String?): Pair<Double, Double>? {
    for (строка in (текст ?: "").split("\n")) {
        if (!строка.startsWith("cpu ")) continue
        val поля = строка.split(Regex("\\s+")).drop(1).take(8).map { число(it, 0.0) ?: 0.0 }
        if (поля.size < 5) return null
        val простой = поля[3] + поля[4]
        return простой to поля.sum()
    }
    return null
}

fun процентЦп(первый: Pair<Double, Double>?, второй: Pair<Double, Double>?): Double? {
    if (первый == null || второй == null) return null
    val разницаВсего = второй.second - первый.second
    val разницаПростоя = второй.first - первый.first
    if (разницаВсего <= 0) return null
    return maxOf(0.0, minOf(100.0, округлить(100.0 * (1.0 - разницаПростоя / разницаВсего), 1)))
}

/** Разбирает /proc/meminfo, значения переводит в байты. */
fun память(текст: String?): Map<String, Long> {
    val значения = mutableMapOf<String, Long>()
    for (строка in (текст ?: "").split("\n")) {
        val двоеточие = строка.indexOf(':')
        if (двоеточие < 0) continue
        val ключ = строка.substring(0, двоеточие).trim()
        val первое = строка.substring(двоеточие + 1).trim().split(Regex("\\s+")).firstOrNull() ?: continue
        val размер = первое.toLongOrNull() ?: continue
        значения[ключ] = размер * 1024
    }
    return значения
}

/** Разбирает вывод df, оставляя только реальные разделы. */
fun диски(текст: String?): List<Накопитель> {
    val список = mutableListOf<Накопитель>()
    for (строка in (текст ?: "").split("\n")) {
        val поля = строка.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (поля.size < 6 || поля[0] == "Filesystem") continue
        val файловаяСистема = поля[0]
        val точка = поля.drop(5).joinToString(" ")
        if (точка != "/" && !файловаяСистема.startsWith("/dev/")) continue
        val всего = поля[1].toLongOrNull() ?: continue
        val занято = поля[2].toLongOrNull() ?: continue
        список.add(
            Накопитель(
                файловаяСистема = файловаяСистема,
                точкаМонтирования = точка,
                всего = всего,
                занято = занято,
                процент = if (всего != 0L) округлить(100.0 * занято / всего, 1) else 0.0,
            )
        )
    }
    return список
}

/** Суммирует принятые и отправленные байты по всем интерфейсам, кроме lo. */
fun сеть(текст: String?): Pair<Long, Long> {
    var принято = 0L
    var отправлено = 0L
    for (строка in (текст ?: "").split("\n")) {
        val двоеточие = строка.indexOf(':')
        if (двоеточие < 0) continue
        val имя = строка.substring(0, двоеточие).trim()
        if (имя.isEmpty() || имя == "lo") continue
        val поля = строка.substring(двоеточие + 1).trim().split(Regex("\\s+"))
        if (поля.size < 9) continue
        val входящие = поля[0].toLongOrNull() ?: continue
        val исходящие = поля[8].toLongOrNull() ?: continue
        принято += входящие
        отправлено += исходящие
    }
    return принято to отправлено
}

/** Разбирает вывод ps: имя, загрузка ЦП и памяти. */
fun процессы(текст: String?): List<Процесс> {
    val список = mutableListOf<Процесс>()
    for (строка in (текст ?: "").split("\n")) {
        val поля = строка.trim().split(Regex("\\s+"), limit = 3)
        if (поля.size < 3) continue
        val цп = число(поля[0]) ?: continue
        val озу = число(поля[1]) ?: continue
        список.add(Процесс(имя = поля[2].trim().take(28), цп = цп, озу = озу))
    }
    return список
}

fun нагрузка(текст: String?): Triple<Double?, Double?, Double?> {
    val поля = (текст ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (поля.size < 3) return Triple(null, null, null)
    return Triple(число(поля[0]), число(поля[1]), число(поля[2]))
}

/** Из «1/249 45781» достаёт число активных и общее число процессов. */
fun процессыИзНагрузки(текст: String?): Pair<Int?, Int?> {
    val поля = (текст ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (поля.size < 4 || !поля[3].contains("/")) return null to null
    val разделённые = поля[3].split("/", limit = 2)
    if (разделённые.size < 2) return null to null
    return разделённые[0].toIntOrNull() to разделённые[1].toIntOrNull()
}

/** Собирает быстрые метрики из частей ответа на `_СНИМОК_БЫСТРО`. */
fun собратьБыстро(
    частиВход: List<String>,
    дискПуть: String,
    счётчики: СчётчикиСети?,
    сейчас: Double,
    времяОпросаМс: Long,
): Метрики {
    var части = частиВход
    if (части.isNotEmpty() && части[0].startsWith("export")) части = части.drop(1)
    if (части.size < 8) {
        throw ОшибкаСвязи(л("Сервер вернул неполный ответ — возможно, команды ограничены оболочкой"), обрезано = true)
    }

    val statПервый = части[0]
    val statВторой = части[1]
    val loadavg = части[2]
    val meminfo = части[3]
    val uptime = части[4]
    val cores = части[5]
    val disks = части[6]
    val net = части[7]

    val память = память(meminfo)
    val всегоОзу = память["MemTotal"]
    val доступноОзу = память["MemAvailable"] ?: память["MemFree"]
    val занятоОзу =
        if (всегоОзу != null && всегоОзу != 0L && доступноОзу != null) всегоОзу - доступноОзу else null
    val всегоПодкачки = память["SwapTotal"] ?: 0L
    val свободноПодкачки = память["SwapFree"] ?: 0L
    val занятоПодкачки = maxOf(0L, всегоПодкачки - свободноПодкачки)
    val процентПодкачки =
        if (всегоПодкачки != 0L) округлить(100.0 * занятоПодкачки / всегоПодкачки, 1) else 0.0

    val списокДисков = диски(disks)
    var основной = списокДисков.firstOrNull { it.точкаМонтирования == дискПуть }
    if (основной == null) основной = списокДисков.firstOrNull { it.точкаМонтирования == "/" }
    if (основной == null && списокДисков.isNotEmpty()) основной = списокДисков.maxBy { it.всего }

    val (принято, отправлено) = сеть(net)
    var скоростьПриёма: Double? = null
    var скоростьОтдачи: Double? = null
    if (счётчики != null) {
        val прошло = maxOf(0.001, сейчас - счётчики.время)
        скоростьПриёма = maxOf(0.0, (принято - счётчики.принято) / прошло)
        скоростьОтдачи = maxOf(0.0, (отправлено - счётчики.отправлено) / прошло)
    }

    val (нагрузка1, нагрузка5, нагрузка15) = нагрузка(loadavg)
    val (активные, всегоПроцессов) = процессыИзНагрузки(loadavg)
    val аптайм = число(uptime.split(Regex("\\s+")).firstOrNull())
    val ядер = cores.trim().split("\n").firstOrNull()?.trim()?.toIntOrNull()

    return Метрики(
        время = сейчас.toLong(),
        цп = процентЦп(времяЦп(statПервый), времяЦп(statВторой)),
        ядер = ядер,
        озуВсего = всегоОзу,
        озуЗанято = занятоОзу,
        озуДоступно = доступноОзу,
        озуПроцент =
            if (всегоОзу != null && всегоОзу != 0L && занятоОзу != null) округлить(100.0 * занятоОзу / всегоОзу, 1) else null,
        подкачкаВсего = всегоПодкачки,
        подкачкаЗанято = занятоПодкачки,
        подкачкаПроцент = процентПодкачки,
        дискПуть = основной?.точкаМонтирования ?: дискПуть,
        дискВсего = основной?.всего,
        дискЗанято = основной?.занято,
        дискПроцент = основной?.процент,
        диски = списокДисков,
        сетьПолучено = принято,
        сетьОтправлено = отправлено,
        скоростьПолучения = скоростьПриёма,
        скоростьОтправки = скоростьОтдачи,
        нагрузка1 = нагрузка1,
        нагрузка5 = нагрузка5,
        нагрузка15 = нагрузка15,
        процессов = всегоПроцессов,
        процессовАктивных = активные,
        аптайм = аптайм?.toLong(),
        времяОпросаМс = времяОпросаМс,
    )
}

/** Собирает подробности из частей ответа на `_СНИМОК_ПОДРОБНО`. */
fun собратьПодробно(частиВход: List<String>, хост: String): Подробности {
    var части = частиВход
    if (части.isNotEmpty() && части[0].startsWith("export")) части = части.drop(1)
    if (части.size < 4) throw ОшибкаСвязи(л("Сервер вернул неполный ответ на запрос подробностей"), обрезано = true)

    val списокСтрок = части[1].split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    val ядро = списокСтрок.firstOrNull() ?: "—"
    val имяХоста = if (списокСтрок.size > 1) списокСтрок[1] else хост

    var температура = число(части[2])
    if (температура != null && температура > 200) температура = округлить(температура / 1000.0, 1)

    return Подробности(
        топПроцессов = процессы(части[0]),
        хост = имяХоста,
        ос = части[3].trim().ifEmpty { "Linux" },
        ядро = ядро,
        температура = температура,
    )
}

// --- службы -----------------------------------------------------------------

/** Разбирает вывод проверки списка служб systemd. */
fun собратьСлужбы(вывод: String): List<Служба> {
    val службы = mutableListOf<Служба>()
    for (строка in вывод.split("\n")) {
        val поля = строка.split("\t")
        if (поля.isEmpty() || поля[0].trim().isEmpty()) continue
        var состояние = (поля.getOrNull(1)?.trim() ?: "").ifEmpty { "unknown" }
        val загрузка = поля.getOrNull(2)?.trim() ?: ""
        if (загрузка == "not-found") состояние = "не найдена"
        службы.add(
            Служба(
                имя = поля[0].trim(),
                состояние = состояние,
                описание = разэкранировать(поля.getOrNull(3)?.trim() ?: ""),
                сМомента = поля.getOrNull(4)?.trim() ?: "",
            )
        )
    }
    return службы
}

// --- безопасность -----------------------------------------------------------

/** Разбирает вывод `fail2ban-client status <jail>`, склеенный маркерами `==имя==`. */
fun разобратьJails(текст: String?): List<Jail> {
    val jails = mutableListOf<Jail>()
    var текущееИмя: String? = null
    var попытки = 0
    var всегоПопыток = 0
    var блокировки = 0
    var всегоБлокировок = 0
    var забаненные = emptyList<String>()

    fun сохранить() {
        val имя = текущееИмя ?: return
        jails.add(
            Jail(
                имя = имя,
                текущиеПопытки = попытки,
                всегоПопыток = всегоПопыток,
                текущиеБлокировки = блокировки,
                всегоБлокировок = всегоБлокировок,
                забаненные = забаненные,
            )
        )
    }

    for (строка in (текст ?: "").split("\n")) {
        val чистая = строка.trim()
        val совпадение = СТРОКА_JAIL.find(чистая)
        if (совпадение != null && совпадение.value == чистая) {
            сохранить()
            текущееИмя = совпадение.groupValues[1].trim()
            попытки = 0
            всегоПопыток = 0
            блокировки = 0
            всегоБлокировок = 0
            забаненные = emptyList()
            continue
        }
        if (текущееИмя == null) continue
        for ((метка, индекс) in ПОЛЯ_JAIL) {
            if (!чистая.contains(метка)) continue
            val найденное = Regex("(\\d+)").find(чистая)?.groupValues?.get(1)?.toIntOrNull()
            if (найденное != null) {
                when (индекс) {
                    0 -> попытки = найденное
                    1 -> всегоПопыток = найденное
                    2 -> блокировки = найденное
                    3 -> всегоБлокировок = найденное
                }
            }
            break
        }
        if (чистая.contains("Banned IP list")) {
            val хвост = чистая.substringAfter(":", "")
            забаненные = хвост.split(Regex("\\s+")).filter { it.isNotBlank() }
        }
    }
    сохранить()
    return jails
}

/** Разбирает строки «... NOTICE [sshd] Ban 1.2.3.4» из журнала fail2ban. */
fun разобратьСобытияFail2ban(текст: String?): List<СобытиеFail2ban> {
    val события = mutableListOf<СобытиеFail2ban>()
    for (строка in (текст ?: "").split("\n")) {
        val совпадение = СТРОКА_СОБЫТИЯ.find(строка.trim()) ?: continue
        if (совпадение.range.first != 0) continue
        val (времяТекст, jail, действие, ip) = совпадение.destructured
        val метка = try {
            LocalDateTime.parse(времяТекст, ФОРМАТ_ВРЕМЕНИ_F2B)
                .atZone(ZoneId.systemDefault()).toEpochSecond()
        } catch (_: Exception) {
            System.currentTimeMillis() / 1000
        }
        события.add(
            СобытиеFail2ban(
                время = метка,
                jail = jail,
                действие = if (действие == "Ban") "ban" else "unban",
                ip = ip,
            )
        )
    }
    return события.sortedByDescending { it.время }
}

/** Достаёт из `ufw status numbered` правила DENY/REJECT с конкретным IP. */
fun разобратьUfw(текст: String?): List<ПравилоUfw> {
    val блокировки = mutableListOf<ПравилоUfw>()
    for (строка in (текст ?: "").split("\n")) {
        val совпадение = СТРОКА_НОМЕРА_UFW.find(строка.trimEnd()) ?: continue
        if (совпадение.range.first != 0) continue
        val номер = совпадение.groupValues[1].toIntOrNull() ?: continue
        val остаток = совпадение.groupValues[2]
        val поля = РАЗДЕЛИТЕЛЬ_ПОЛЕЙ.split(остаток.trim()).filter { it.isNotEmpty() }
        if (поля.size < 3) continue
        val куда = поля[0]
        val действие = поля[1]
        val откуда = поля[2]
        val верхнее = действие.uppercase()
        if (!верхнее.startsWith("DENY") && !верхнее.startsWith("REJECT")) continue
        val адрес = откуда.replace("(v6)", "").trim()
        val адресОснова = адрес.split("/").first().trim()
        if (адресОснова.lowercase() == "anywhere" || !корректныйIp(адресОснова)) continue
        блокировки.add(ПравилоUfw(номер = номер, ip = адресОснова, куда = куда.trim()))
    }
    return блокировки
}

/** Собирает данные безопасности из частей ответа на `_СНИМОК_БЕЗОПАСНОСТИ`. */
fun собратьБезопасность(частиВход: List<String>, сейчас: Long): Безопасность {
    var части = частиВход
    val первая = части.firstOrNull()
    if (первая != null && (первая.startsWith("export") || первая.startsWith("sudo") || первая.isBlank())) {
        части = части.drop(1)
    }
    if (части.size < 3) {
        throw ОшибкаСвязи(л("Сервер вернул неполный ответ на запрос данных безопасности"), обрезано = true)
    }
    return Безопасность(
        время = сейчас,
        jails = разобратьJails(части[0]),
        события = разобратьСобытияFail2ban(части[1]),
        блокировки = разобратьUfw(части[2]),
    )
}

// --- обслуживание сервера ----------------------------------------------------

/**
 * Сколько блоков печатает `_СНИМОК_ОБСЛУЖИВАНИЕ`; меньше — ответ обрезан.
 * Последний блок — строка «конец снимка»: без неё пустой хвост (например,
 * отсутствующий smartctl) срезался бы вместе с блоком, и обрыв ответа стало бы
 * невозможно отличить от пустого вывода команды.
 */
private const val ЧАСТЕЙ_ОБСЛУЖИВАНИЯ = 25

/** Метка устройства в выводе SMART-блока. */
internal const val МЕТКА_ДИСКА = "@@диск@@"

private val ИМЯ_ЕДИНИЦЫ = Regex("^[\\w@.\\-]+\\.(service|socket|timer|mount|target|path|slice|device|swap)$")
private val ВЕРСИЯ_ЯДРА = Regex("^linux-image-(\\d+)\\.(\\d+)\\.(\\d+)-(\\d+)-")

/**
 * Разбирает краткую запись размера: «80.0M», «1,2G», «238.5G», «512 KiB».
 * Так размеры печатают `journalctl --disk-usage`, `du` и `lsblk`.
 */
fun размерИзКраткой(текст: String?): Long? {
    val строка = (текст ?: "").replace(',', '.').trim()
    val совпадение = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*([kKmMgGtTpP]?)(?:i?[bB])?").find(строка) ?: return null
    val значение = совпадение.groupValues[1].toDoubleOrNull() ?: return null
    val единица = совпадение.groupValues[2].uppercase()
    val множитель = when (единица) {
        "K" -> 1024.0
        "M" -> 1024.0 * 1024
        "G" -> 1024.0 * 1024 * 1024
        "T" -> 1024.0 * 1024 * 1024 * 1024
        "P" -> 1024.0 * 1024 * 1024 * 1024 * 1024
        else -> 1.0
    }
    return (значение * множитель).toLong()
}

/** Числовая версия ядра (АБИ) — по ней сравнивают, какое ядро новее. */
private fun версияЯдра(имяПакета: String): List<Int> =
    ВЕРСИЯ_ЯДРА.find(имяПакета)?.groupValues?.drop(1)?.mapNotNull { it.toIntOrNull() } ?: emptyList()

/** Сравнение версий ядра по числовым частям: 5.15.0.191 новее 5.15.0.190. */
private val СРАВНЕНИЕ_ЯДЕР = Comparator<String> { а, б ->
    val частиА = версияЯдра(а)
    val частиБ = версияЯдра(б)
    var итог = 0
    for (индекс in 0 until maxOf(частиА.size, частиБ.size)) {
        val разница = частиА.getOrElse(индекс) { 0 } - частиБ.getOrElse(индекс) { 0 }
        if (разница != 0) {
            итог = разница
            break
        }
    }
    итог
}

/**
 * Разбирает снимок аудита обслуживания: обновления пакетов, службы, журналы,
 * время, место на дисках, накопители и повторяющиеся точки монтирования.
 */
fun собратьОбслуживание(частиВход: List<String>, сейчас: Long): Обслуживание {
    var части = частиВход
    if (части.isNotEmpty() && (части[0].startsWith("export") || части[0].isBlank())) части = части.drop(1)
    if (части.size < ЧАСТЕЙ_ОБСЛУЖИВАНИЯ) {
        throw ОшибкаСвязи(л("Сервер вернул неполный ответ на запрос обслуживания"), обрезано = true)
    }

    val аптайм = число(части[0].split(Regex("\\s+")).firstOrNull())
    val ядро = части[2].trim().split("\n").firstOrNull()?.trim().orEmpty()

    val установленные = части[3].split("\n")
        .mapNotNull { строка -> строка.split("\t").firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } }
        .filter { ВЕРСИЯ_ЯДРА.containsMatchIn(it) }
    val последнееЯдро = установленные.maxWithOrNull(СРАВНЕНИЕ_ЯДЕР)
        ?.removePrefix("linux-image-")
        ?.trim()
        .orEmpty()

    val сбойные = части[8].split("\n")
        .mapNotNull { строка -> строка.replace("●", " ").trim().split(Regex("\\s+")).firstOrNull() }
        .filter { ИМЯ_ЕДИНИЦЫ.matches(it) }

    val крупныеЛоги = части[11].split("\n").mapNotNull { строка ->
        val поля = строка.trim().split("\t")
        val мб = поля.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
        val путь = поля.getOrNull(1)?.trim().orEmpty()
        if (путь.isEmpty()) null else КрупныйЛог(путь, мб * 1024 * 1024)
    }

    val времяНастроено = части[12].split("\n")
        .map { строка -> строка.trim().split("=", limit = 2) }
        .filter { it.size == 2 }
        .associate { it[0] to it[1] }
    val ntp = when (времяНастроено["NTPSynchronized"]?.lowercase()) {
        "yes" -> true
        "no" -> false
        else -> null
    }
    val службаВремени = части[13].split("\n")
        .map { строка -> строка.trim().split("=", limit = 2) }
        .filter { it.size == 2 }
        .firstOrNull { it[1] == "active" }
        ?.let { л("{1} — работает", it[0]) }
        .orEmpty()

    val загрузка = Regex("=\\s*([0-9]+(?:[.,][0-9]+)?)\\s*s").find(части[14])
        ?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    var дискВсего: Long? = null
    var дискСвободно: Long? = null
    var дискЗанято: Double? = null
    части[15].split("\n").lastOrNull { it.isNotBlank() }?.let { строка ->
        val поля = строка.trim().split(Regex("\\s+"))
        if (поля.size >= 6) {
            дискВсего = поля[1].toLongOrNull()
            дискСвободно = поля[3].toLongOrNull()
            дискЗанято = поля[4].removeSuffix("%").replace(',', '.').toDoubleOrNull()
        }
    }

    val память = память(части[16])
    val свопВсего = память["SwapTotal"] ?: 0L
    val свопСвободно = память["SwapFree"] ?: 0L

    val состоянияЮнитов = части[19].split("\n").map { it.trim() }
    val автообновления = состоянияЮнитов.getOrNull(0) == "enabled"
    val ротацияЛогов = состоянияЮнитов.getOrNull(2)?.let { it == "active" || it == "waiting" }

    val смонтированные = части[21].split("\n").map { it.trim() }.filter { it.startsWith("/dev/") }
    val накопители = части[20].split("\n").mapNotNull { строка ->
        val поля = строка.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val имя = поля.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        if (имя.startsWith("loop") || имя.startsWith("ram")) return@mapNotNull null
        val размер = размерИзКраткой(поля.getOrNull(1)) ?: 0L
        НакопительАудита(
            устройство = имя,
            размер = размер,
            модель = поля.drop(2).joinToString(" "),
            смонтирован = смонтированные.any { it.startsWith("/dev/$имя") },
        )
    }

    val точкиМонтирования = части[22].split("\n").mapNotNull { строка ->
        val поля = строка.trim().split(Regex("\\s+"))
        if (поля.size < 2) null else поля[1].takeIf { it.startsWith("/") }
    }
    val дубли = точкиМонтирования.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.sorted()

    return Обслуживание(
        время = сейчас,
        аптайм = аптайм?.toLong(),
        ядро = ядро,
        ядроУстановлено = последнееЯдро,
        перезагрузкаТребуется = части[1].contains("restart required", true),
        перезагрузкаЯдра = последнееЯдро.isNotEmpty() && ядро.isNotEmpty() && последнееЯдро != ядро,
        обновленийВсего = целое(части[5].lines().firstOrNull()),
        обновленийСейчас = целое(части[4].lines().firstOrNull()),
        обновленийБезопасности = целое(части[6].lines().firstOrNull()),
        удалитьПакетов = целое(части[7].lines().firstOrNull()),
        пакетовУстановлено = целое(части[17].lines().firstOrNull()),
        автозапускЮнитов = целое(части[18].lines().firstOrNull()),
        сбойныеЮниты = сбойные,
        размерЖурнала = размерИзКраткой(части[9]),
        размерЛогов = целое(части[10].lines().firstOrNull())?.toLong(),
        крупныеЛоги = крупныеЛоги,
        дискЗанято = дискЗанято,
        дискВсего = дискВсего,
        дискСвободно = дискСвободно,
        памятьВсего = память["MemTotal"],
        памятьДоступно = память["MemAvailable"],
        свопВсего = свопВсего,
        свопЗанято = maxOf(0L, свопВсего - свопСвободно),
        ntpСинхронизирован = ntp,
        часовойПояс = времяНастроено["Timezone"].orEmpty(),
        службаВремени = службаВремени,
        загрузкаСекунд = загрузка,
        автообновленияВключены = автообновления,
        ротацияЛоговВключена = ротацияЛогов,
        дублиВFstab = дубли,
        накопители = накопители,
        smartДоступен = части[23].trim().isNotEmpty() && части[23].contains("/"),
    )
}

/**
 * Разбирает вывод SMART-блока: для каждого устройства — итог самотестирования,
 * переназначенные сектора, температура и наработка в часах.
 */
fun разобратьЗдоровьеДисков(вывод: String?): List<ЗдоровьеДиска> {
    val итог = mutableListOf<ЗдоровьеДиска>()
    var устройство: String? = null
    var статус = ""
    var переназначено: Long? = null
    var температура: Double? = null
    var часы: Long? = null

    fun сохранить() {
        val имя = устройство ?: return
        // Блок без единой строки данных (обычный случай, когда smartmontools не
        // установлен, а метки дисков сервер всё равно печатает) пропускаем: иначе
        // на экране появились бы красные строки «нет ответа» вместо подсказки
        // поставить smartmontools.
        val естьДанные = статус.isNotBlank() || переназначено != null ||
            температура != null || часы != null
        if (!естьДанные) return
        итог.add(ЗдоровьеДиска(имя, статус, переназначено, температура, часы))
    }

    for (строка in (вывод ?: "").split("\n")) {
        val текст = строка.trim()
        if (текст.startsWith(МЕТКА_ДИСКА)) {
            сохранить()
            устройство = текст.removePrefix(МЕТКА_ДИСКА).trim()
            статус = ""
            переназначено = null
            температура = null
            часы = null
            continue
        }
        if (устройство == null || текст.isEmpty()) continue
        if (текст.startsWith("итог ")) {
            статус = текст.removePrefix("итог ").trim()
            continue
        }
        val поля = текст.split(Regex("\\s+"))
        if (поля.size < 10) continue
        val значение = поля[9].toLongOrNull() ?: continue
        when (поля[0]) {
            "5", "197" -> переназначено = (переназначено ?: 0L) + значение
            "9" -> часы = значение
            "194" -> температура = значение.toDouble()
        }
    }
    сохранить()
    return итог
}

// --- автопоиск целей для мастера настройки (чистые функции) ------------

private val ЗАГЛУШКИ_ДОМЕНОВ = listOf("example.com", "example.org", "example.net")

/** Полное доменное имя: метки из букв/цифр/дефиса (не по краям), минимум одна точка. */
private val РЕГЕКС_ХОСТНЕЙМ = Regex(
    "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$"
)

/** Ключевые слова директив, из которых берутся адреса сайтов. */
private val КЛЮЧИ_ДОМЕНОВ = listOf("server_name", "servername")

/** Октеты IPv4 или null, если это не адрес (ведущие нули «010» тоже не адрес). */
internal fun октетыIPv4(значение: String): List<Int>? {
    val части = значение.split(".")
    if (части.size != 4) return null
    val октеты = части.map { часть -> часть.toIntOrNull()?.takeIf { it in 0..255 && часть == it.toString() } }
    if (октеты.any { it == null }) return null
    return октеты.filterNotNull()
}

/**
 * Служебные адреса IPv4, которые сайтом быть не могут: «этот хост» (0/8),
 * loopback (127/8), link-local или APIPA (169.254/16), multicast и
 * зарезервированное (224/4 и выше).
 *
 * Приватные диапазоны RFC 1918 (10/8, 172.16/12, 192.168/16) сюда
 * намеренно НЕ входят: сервер в локальной сети — обычное дело, и его
 * виртуальный хост вида `server_name 192.168.0.2;` это настоящий сайт
 * (именно так работает локальный ASTUS_PROJECT). Раньше такие адреса
 * отбрасывались вместе с мусором, и сайт пропадал из мастера.
 */
internal fun служебныйIPv4(октеты: List<Int>): Boolean {
    val (a, b) = октеты
    return a == 0 || a == 127 || a >= 224 || (a == 169 && b == 254)
}

/** Настоящая проверка: реальный домен (с точкой) или адрес IPv4 — иначе не сайт. */
internal fun похожеНаДомен(значение: String): Boolean {
    if (значение.isEmpty() || значение == "_" || значение == "localhost") return false
    if (значение.contains('*') || значение.contains("${'$'}") || значение.contains('~')) return false
    if (значение.endsWith(".local")) return false
    if (ЗАГЛУШКИ_ДОМЕНОВ.any { заглушка -> значение == заглушка || значение.endsWith(".$заглушка") }) return false

    val октеты = октетыIPv4(значение)
    if (октеты != null) return !служебныйIPv4(октеты)

    return РЕГЕКС_ХОСТНЕЙМ.matches(значение)
}

/**
 * Адреса из директив `server_name` (nginx) и `ServerName` (Apache) — заготовки
 * для списка сайтов мастера.
 *
 * Команда вырезает комментарии nginx (часть строки от символа «#») ещё до поиска:
 * без этого настоящая строка конфигурации `#  1. add the domain to server_name
 * (or add a second server block);` совпадала с шаблоном `server_name[[:space:]]+[^;]+`
 * и добавляла в список «сайтов» слова `(or`, `add`, `a`, `second`, `server`,
 * `block`. Разбор защищён и со своей стороны: директивой считается только
 * строка, где ключевое слово стоит первым, а каждое слово после него обязано
 * быть настоящим доменом или IP-адресом.
 *
 * Повторы убираются, порядок строк сохраняется.
 */
internal fun доменыИзServerName(вывод: String?): List<String> {
    val домены = LinkedHashSet<String>()
    for (строка in (вывод ?: "").split("\n")) {
        val куски = строка.trim().split(Regex("\\s+"))
        if (куски.isEmpty() || !КЛЮЧИ_ДОМЕНОВ.contains(куски[0].lowercase())) continue
        for (кусок in куски.drop(1)) {
            val домен = кусок.trim().trimEnd(';').lowercase()
            if (похожеНаДомен(домен)) домены.add(домен)
        }
    }
    return домены.toList()
}

/**
 * Служебные юниты systemd, которые в мастере не нужны: внутренние части
 * systemd, сессии пользователей, консольные getty, шина D-Bus, polkit,
 * пакеты snap и ModemManager. Они всегда есть в выдаче `systemctl`, но к
 * проектам на сервере отношения не имеют.
 */
private val ШУМ_СЛУЖБ = listOf("systemd-", "user@", "getty@", "dbus", "polkit", "snap.", "ModemManager")

/** Службы без служебного шума — их и показывает мастер. */
internal fun безШумаСлужб(службы: List<Pair<String, String>>): List<Pair<String, String>> =
    службы.filter { пара -> ШУМ_СЛУЖБ.none { шум -> пара.first.startsWith(шум) } }

/**
 * Кандидаты служб из вывода `systemctl list-units --type=service --state=running`:
 * пары «имя.service → описание». Строка вывода выглядит как
 * `nginx.service loaded active running A high performance web server`,
 * поэтому описание — всё после четвёртого столбца. Строки без `.service`
 * (заголовок таблицы, легенда, мусор) пропускаются, повторы имён убираются.
 */
internal fun службыИзВывода(вывод: String?): List<Pair<String, String>> {
    val службы = mutableListOf<Pair<String, String>>()
    val встреченные = mutableSetOf<String>()
    for (строка in (вывод ?: "").split("\n")) {
        val куски = строка.trim().split(Regex("\\s+"))
        val имя = куски.firstOrNull()?.takeIf { it.endsWith(".service") } ?: continue
        if (!встреченные.add(имя)) continue
        службы.add(имя to куски.drop(4).joinToString(" ").trim())
    }
    return службы
}
