package ru.serverastus.monitor.сеть

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import ru.serverastus.monitor.данные.Локаль
import ru.serverastus.monitor.данные.ПравилоUfw

/**
 * Разбор ответов сервера проверяется на реальных снимках с сервера
 * (`src/test/resources/снимки`). Ожидаемые значения сняты настольной версией
 * (`server_monitor/ssh_linux.py`) на тех же снимках, поэтому тесты следят за
 * тем, чтобы мобильный разбор не разошёлся с эталонным.
 */
class РазборТест {

    @Before
    fun фиксируемРусскийЯзык() {
        Локаль.подменитьЯзык("ru")
    }

    @After
    fun возвращаемСистемныйЯзык() {
        Локаль.подменитьЯзык(null)
    }

    private fun снимок(имя: String): String {
        val поток = javaClass.getResourceAsStream("/снимки/$имя.txt")
        checkNotNull(поток) { "Не найден снимок ответа сервера: $имя" }
        return поток.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Test
    fun `снимок быстрых метрик делится на восемь частей`() {
        assertEquals(8, разделить(снимок("быстро")).size)
    }

    @Test
    fun `быстрые метрики совпадают с эталонными`() {
        val метрики = собратьБыстро(разделить(снимок("быстро")), "/", null, 1789586335.0, 7L)

        assertEquals(0.6, метрики.цп!!, 1e-9)
        assertEquals(4, метрики.ядер)
        assertEquals(16_674_275_328L, метрики.озуВсего)
        assertEquals(1_380_691_968L, метрики.озуЗанято)
        assertEquals(15_293_583_360L, метрики.озуДоступно)
        assertEquals(8.3, метрики.озуПроцент!!, 1e-9)
        assertEquals(4_294_963_200L, метрики.подкачкаВсего)
        assertEquals(0L, метрики.подкачкаЗанято)
        assertEquals(0.0, метрики.подкачкаПроцент, 1e-9)
        assertEquals("/", метрики.дискПуть)
        assertEquals(249_792_131_072L, метрики.дискВсего)
        assertEquals(16_843_571_200L, метрики.дискЗанято)
        assertEquals(6.7, метрики.дискПроцент!!, 1e-9)
        assertEquals(216_555_799L, метрики.сетьПолучено)
        assertEquals(617_096_885L, метрики.сетьОтправлено)
        assertNull(метрики.скоростьПолучения)
        assertNull(метрики.скоростьОтправки)
        assertEquals(0.05, метрики.нагрузка1!!, 1e-9)
        assertEquals(0.08, метрики.нагрузка5!!, 1e-9)
        assertEquals(0.03, метрики.нагрузка15!!, 1e-9)
        assertEquals(252, метрики.процессов)
        assertEquals(1, метрики.процессовАктивных)
        assertEquals(170_676L, метрики.аптайм)
        assertEquals(7L, метрики.времяОпросаМс)
        assertEquals(1789586335L, метрики.время)
    }

    @Test
    fun `разделы диска разбираются полностью`() {
        val диски = собратьБыстро(разделить(снимок("быстро")), "/", null, 1789586335.0, 0L).диски

        assertEquals(2, диски.size)
        assertEquals("/dev/sda2", диски[0].файловаяСистема)
        assertEquals("/", диски[0].точкаМонтирования)
        assertEquals(249_792_131_072L, диски[0].всего)
        assertEquals(16_843_571_200L, диски[0].занято)
        assertEquals(6.7, диски[0].процент, 1e-9)
        assertEquals("/dev/sda1", диски[1].файловаяСистема)
        assertEquals("/boot/efi", диски[1].точкаМонтирования)
        assertEquals(1_124_995_072L, диски[1].всего)
        assertEquals(8_015_872L, диски[1].занято)
        assertEquals(0.7, диски[1].процент, 1e-9)
    }

    @Test
    fun `скорость сети считается по двум замерам`() {
        val части = разделить(снимок("быстро"))
        val первый = собратьБыстро(части, "/", null, 1000.0, 0L)
        val второй = собратьБыстро(части, "/", СчётчикиСети(990.0, 216_000_000L, 617_000_000L), 1000.0, 0L)

        assertEquals(0L, первый.сетьПолучено - второй.сетьПолучено)
        assertEquals((216_555_799L - 216_000_000L) / 10.0, второй.скоростьПолучения!!, 1e-6)
        assertEquals((617_096_885L - 617_000_000L) / 10.0, второй.скоростьОтправки!!, 1e-6)
    }

    @Test
    fun `подробности совпадают с эталонными`() {
        val подробности = собратьПодробно(разделить(снимок("подробно")), "запасной")

        assertEquals("serverastus", подробности.хост)
        assertEquals("5.15.0-191-generic", подробности.ядро)
        assertEquals("Ubuntu 22.04.5 LTS", подробности.ос)
        assertEquals(50.0, подробности.температура!!, 1e-9)

        val процессы = подробности.топПроцессов
        assertEquals(8, процессы.size)
        assertEquals("sshd", процессы[0].имя)
        assertEquals(6.0, процессы[0].цп, 1e-9)
        assertEquals(0.0, процессы[0].озу, 1e-9)
        assertEquals("mysqld", процессы[1].имя)
        assertEquals(1.1, процессы[1].цп, 1e-9)
        assertEquals(2.6, процессы[1].озу, 1e-9)
        assertEquals("gunicorn", процессы[2].имя)
        assertEquals("redis-server", процессы[3].имя)
        assertEquals("python", процессы[4].имя)
        assertEquals("systemd", процессы[5].имя)
    }

    @Test
    fun `службы совпадают с эталонными`() {
        val службы = собратьСлужбы(снимок("службы"))

        assertEquals(9, службы.size)
        assertEquals("nginx", службы[0].имя)
        assertEquals("active", службы[0].состояние)
        assertEquals("работает", службы[0].состояниеТекст)
        assertEquals("A high performance web server and a reverse proxy server", службы[0].описание)
        assertEquals("Wed 2026-09-16 06:16:21 EEST", службы[0].сМомента)
        assertEquals(true, службы[0].работает)

        assertEquals("postgresql@14-main", службы[2].имя)
        assertEquals("PostgreSQL Cluster 14-main", службы[2].описание)

        val несуществующая = службы.last()
        assertEquals("несуществующая", несуществующая.имя)
        assertEquals("не найдена", несуществующая.состояние)
        assertEquals("не найдена", несуществующая.состояниеТекст)
        assertEquals("несуществующая.service", несуществующая.описание)
        assertEquals(false, несуществующая.работает)
    }

    @Test
    fun `безопасность совпадает с эталонной`() {
        val безопасность = собратьБезопасность(разделить(снимок("безопасность")), 1789586335L)

        assertEquals(1, безопасность.jails.size)
        val jail = безопасность.jails[0]
        assertEquals("sshd", jail.имя)
        assertEquals(76, jail.текущиеПопытки)
        assertEquals(1548, jail.всегоПопыток)
        assertEquals(0, jail.текущиеБлокировки)
        assertEquals(29, jail.всегоБлокировок)
        assertEquals(0, jail.забаненные.size)

        val события = безопасность.события
        assertEquals(10, события.size)
        assertEquals("unban", события[0].действие)
        assertEquals("sshd", события[0].jail)
        assertEquals("203.0.113.1", события[0].ip)
        assertEquals(1789568778L, события[0].время)
        assertEquals("unban", события[1].действие)
        assertEquals("203.0.113.2", события[1].ip)
        assertEquals(1789567492L, события[1].время)
        assertEquals("ban", события[2].действие)
        assertEquals("203.0.113.1", события[2].ip)
        assertEquals("ban", события.last().действие)
        assertEquals(1789530221L, события.last().время)
        // События идут от свежих к старым.
        assertEquals(события.map { it.время }.sortedDescending(), события.map { it.время })

        assertEquals(0, безопасность.блокировки.size)
    }

    @Test
    fun `вывод ufw без прав root не даёт блокировок`() {
        assertEquals(emptyList<ПравилоUfw>(), разобратьUfw(снимок("ufw_вывод")))
    }

    @Test
    fun `правила ufw отбирают только DENY и REJECT с конкретным адресом`() {
        val вывод = """
            [ 1] 22/tcp                     ALLOW       Anywhere
            [ 2] Anywhere                   DENY        198.51.100.7
            [ 3] 443/tcp                    LIMIT       Anywhere
            [ 4] Anywhere                   REJECT      198.51.100.8 (v6)
            [ 5] Anywhere                   DENY        198.51.100.0/24
            [ 6] Anywhere                   DENY        Anywhere
        """.trimIndent()

        val правила = разобратьUfw(вывод)

        // Подсеть приводится к базовому адресу, как в настольной версии, поэтому
        // правило для 198.51.100.0/24 тоже попадает в список.
        assertEquals(3, правила.size)
        assertEquals(ПравилоUfw(2, "198.51.100.7", "Anywhere"), правила[0])
        assertEquals(ПравилоUfw(4, "198.51.100.8", "Anywhere"), правила[1])
        assertEquals(ПравилоUfw(5, "198.51.100.0", "Anywhere"), правила[2])
    }

    @Test
    fun `адреса проверяются как в эталоне`() {
        assertEquals(true, корректныйIp("1.2.3.4"))
        assertEquals(false, корректныйIp("192.168.0.2/24"))
        assertEquals(false, корректныйIp("anywhere"))
        assertEquals(true, корректныйIp("2001:db8::1"))
        assertEquals(false, корректныйIp("мусор"))
        assertEquals(false, корректныйIp(""))
        assertEquals(false, корректныйIp(null))
        assertEquals(false, корректныйIp("999.1.1.1"))
        assertEquals(true, корректныйIp(" 10.0.0.1 "))
    }

    @Test
    fun `экранирование снимается как в эталоне`() {
        assertEquals("a b", разэкранировать("a\\x20b"))
        assertEquals("нет", разэкранировать("нет"))
        assertEquals("", разэкранировать(""))
        assertEquals("", разэкранировать(null))
    }

    @Test
    fun `неполный ответ на быстрый снимок распознаётся как ошибка`() {
        val ошибка = try {
            собратьБыстро(listOf("a", "b"), "/", null, 0.0, 0L)
            null
        } catch (ошибка: ОшибкаСвязи) {
            ошибка
        }
        assertEquals("Сервер вернул неполный ответ — возможно, команды ограничены оболочкой", ошибка?.message)
    }
}
