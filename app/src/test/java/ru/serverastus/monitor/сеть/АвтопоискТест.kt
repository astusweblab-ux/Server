package ru.serverastus.monitor.сеть

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.serverastus.monitor.данные.естьСлужба
import ru.serverastus.monitor.данные.естьЭлемент
import ru.serverastus.monitor.данные.ключАдреса
import ru.serverastus.monitor.данные.привестиСписокСлужб

/**
 * Автопоиск целей мастера на настоящих ответах сервера.
 *
 * Фикстуры сняты с рабочего сервера:
 * - `снимки/мастер_сайты.txt` — вывод команды мастера по nginx (`server_name`);
 * - `снимки/мастер_службы.txt` — `systemctl list-units --type=service --state=running --no-legend`.
 */
class АвтопоискТест {

    private fun снимок(имя: String): String =
        javaClass.getResourceAsStream("/снимки/$имя.txt")?.bufferedReader()?.use { it.readText() }
            ?: error("нет фикстуры $имя")

    // --- сайты -----------------------------------------------------------

    @Test
    fun `из настоящей конфигурации nginx находятся только реальные сайты`() {
        assertEquals(
            listOf("blembula.pp.ua", "192.0.2.10", "192.168.0.2", "zsulink.pp.ua", "dealscout.pp.ua"),
            доменыИзServerName(снимок("мастер_сайты")),
        )
    }

    @Test
    fun `мусорные слова из комментариев nginx сайтами не становятся`() {
        // Настоящая строка из конфигурации: с вырезанием комментария команда её не
        // увидит, но разбор обязан быть устойчив и к ней.
        val сырой = """
            |#   1. add the domain to server_name (or add a second server block);
            |    server_name blembula.pp.ua;
            |    server_name _;
        """.trimMargin()
        assertEquals(listOf("blembula.pp.ua"), доменыИзServerName(сырой))
        for (мусор in listOf("(or", "add", "a", "second", "server", "block")) {
            assertFalse(доменыИзServerName(сырой).contains(мусор))
        }
    }

    @Test
    fun `локальный адрес проекта остается сайтом`() {
        // ASTUS_PROJECT описан как `server_name 192.168.0.2;` — приватный адрес
        // не повод выбрасывать настоящий виртуальный хост.
        assertEquals(listOf("192.168.0.2"), доменыИзServerName("    server_name 192.168.0.2;"))
    }

    @Test
    fun `служебные адреса и заглушки сайтами не считаются`() {
        val служебные = """
            |    server_name _;
            |    server_name localhost;
            |    server_name example.com;
            |    server_name 0.0.0.0;
            |    server_name 127.0.0.1;
            |    server_name 169.254.10.1;
            |    server_name 239.0.0.1;
            |    server_name *.test.ua;
            |    server_name ~^www\.example\.ua${'$'};
            |    server_name сервер;
        """.trimMargin()
        assertEquals(emptyList<String>(), доменыИзServerName(служебные))
    }

    @Test
    fun `повторяющиеся адреса показываются один раз`() {
        val сПовторами = """
            |    server_name blembula.pp.ua 192.0.2.10;
            |    server_name BLEMBULA.pp.ua;
            |    server_name 192.0.2.10;
        """.trimMargin()
        assertEquals(listOf("blembula.pp.ua", "192.0.2.10"), доменыИзServerName(сПовторами))
    }

    @Test
    fun `сайты одного сервера не считаются добавленными у другого`() {
        val сайтыПервого = listOf("https://blembula.pp.ua", "http://192.168.0.2")
        // Флажок «добавлено» считается по списку проверок текущего сервера, а не
        // по глобальному состоянию: у второго сервера список свой и пустой.
        assertTrue(естьЭлемент(сайтыПервого, ключАдреса("https://BLEMBULA.pp.ua/")) { ключАдреса(it) })
        assertTrue(естьЭлемент(сайтыПервого, ключАдреса("http://192.168.0.2/")) { ключАдреса(it) })
        assertFalse(естьЭлемент(сайтыПервого, ключАдреса("https://dealscout.pp.ua")) { ключАдреса(it) })
        assertFalse(естьЭлемент(emptyList<String>(), ключАдреса("https://blembula.pp.ua")) { ключАдреса(it) })
    }

    // --- службы ----------------------------------------------------------

    @Test
    fun `службы разбираются из настоящего вывода systemctl`() {
        val службы = службыИзВывода(снимок("мастер_службы"))
        assertEquals(35, службы.size)
        assertTrue(службы.all { пара -> пара.first.endsWith(".service") })
        for (нужная in listOf(
            "nginx.service", "mysql.service", "postgresql@14-main.service", "postgresql@17-main.service",
            "redis-server.service", "fail2ban.service", "vsftpd.service", "ssh.service",
        )) {
            assertTrue(нужная, службы.any { пара -> пара.first == нужная })
        }
        assertEquals("A high performance web server and a reverse proxy server", описание(службы, "nginx.service"))
    }

    @Test
    fun `заголовок и легенда вывода службами не становятся`() {
        val сырой = """
            |UNIT LOAD ACTIVE SUB DESCRIPTION
            |nginx.service loaded active running A high performance web server
            |35 loaded units listed.
        """.trimMargin()
        assertEquals(listOf("nginx.service" to "A high performance web server"), службыИзВывода(сырой))
    }

    @Test
    fun `служебный шум systemd отфильтрован и рабочие службы остаются`() {
        val оставшиеся = безШумаСлужб(службыИзВывода(снимок("мастер_службы")))
        assertEquals(24, оставшиеся.size)
        assertEquals(ОЖИДАЕМЫЕ_СЛУЖБЫ, оставшиеся.map { пара -> пара.first }.sorted())
        for (шум in listOf("systemd-", "user@", "getty@", "dbus", "polkit", "snap.", "ModemManager")) {
            assertFalse(шум, оставшиеся.any { пара -> пара.first.startsWith(шум) })
        }
    }

    @Test
    fun `сохраненные имена без суффикса считаются уже добавленными`() {
        // Ровно тот случай, из-за которого служба предлагалась к добавлению повторно:
        // в настройках лежит «ssh», а сервер отдаёт «ssh.service».
        val старыеЗаписи = ОЖИДАЕМЫЕ_СЛУЖБЫ.map { имя -> имя.removeSuffix(".service") }
        val найденные = безШумаСлужб(службыИзВывода(снимок("мастер_службы")))
        for (пара in найденные) {
            assertTrue(пара.first, естьСлужба(старыеЗаписи, пара.first))
        }
        assertTrue(естьСлужба(listOf("ssh", "cron"), "ssh.service"))
        assertTrue(естьСлужба(listOf("ssh", "cron"), "cron.service"))
        assertFalse(естьСлужба(listOf("ssh", "cron"), "vsftpd.service"))
    }

    @Test
    fun `повторное добавление службы не создает дубль`() {
        val список = mutableListOf<String>()
        val сохранённые = привестиСписокСлужб(listOf("ssh", "ssh.service", " SSH.SERVICE ", "cron.service", "cron"))
        assertEquals(listOf("ssh.service", "cron.service"), сохранённые)
        assertTrue(естьСлужба(сохранённые, "ssh"))
        assertFalse(естьСлужба(сохранённые, ""))
        assertEquals(listOf("nginx.service"), привестиСписокСлужб(listOf("nginx.service", "nginx")))
        список.addAll(сохранённые)
        assertEquals(2, список.size)
    }

    private fun описание(службы: List<Pair<String, String>>, имя: String): String =
        службы.first { пара -> пара.first == имя }.second

    private companion object {
        /** 24 рабочие службы сервера — эталон для фильтра шума и проверки «уже добавлено». */
        val ОЖИДАЕМЫЕ_СЛУЖБЫ = listOf(
            "astus-projects.service", "blembula.service", "cron.service", "dealscout.service",
            "fail2ban.service", "fwupd.service", "irqbalance.service", "mysql.service",
            "networkd-dispatcher.service", "nginx.service", "postgresql@14-main.service",
            "postgresql@17-main.service", "redis-server.service", "rsyslog.service",
            "setevaya-strazha.service", "smartmontools.service", "snapd.service", "ssh.service",
            "thermald.service", "udisks2.service", "unattended-upgrades.service", "upower.service",
            "vsftpd.service", "zsu-license.service",
        ).sorted()
    }
}
