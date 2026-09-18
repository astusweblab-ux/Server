package ru.serverastus.monitor.экран

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.serverastus.monitor.данные.Локаль
import ru.serverastus.monitor.данные.добавитьБезДубля
import ru.serverastus.monitor.данные.добавитьСлужбу
import ru.serverastus.monitor.данные.ключАдреса
import ru.serverastus.monitor.данные.нормализоватьСлужбу
import ru.serverastus.monitor.данные.привестиСписокСлужб

/** Проверки помощников мастера настройки: адреса сайтов, подписи портов и имён служб. */
class МастерТест {

    @Before
    fun фиксируемРусскийЯзык() {
        Локаль.подменитьЯзык("ru")
    }

    @After
    fun возвращаемСистемныйЯзык() {
        Локаль.подменитьЯзык(null)
    }

    @Test
    fun `домен найденного сайта получает https при наличии 443`() {
        assertEquals("https://blembula.pp.ua", адресКандидата("blembula.pp.ua", есть443 = true))
        assertEquals("http://blembula.pp.ua", адресКандидата("blembula.pp.ua", есть443 = false))
    }

    @Test
    fun `голый ip проверяется без ssl`() {
        assertEquals("http://192.0.2.10", адресКандидата("192.0.2.10", есть443 = true))
        assertEquals("http://192.168.0.2", адресКандидата("192.168.0.2", есть443 = true))
    }

    @Test
    fun `схема добавляется к адресу, если её нет`() {
        assertEquals("https://мой-сайт.ру", привестиАдрес("мой-сайт.ру"))
        assertEquals("https://сайт.ру", привестиАдрес("https://сайт.ру/"))
        assertEquals("http://192.168.0.2", привестиАдрес("192.168.0.2"))
        assertEquals("", привестиАдрес("   "))
    }

    @Test
    fun `известные порты подписаны по имени службы`() {
        assertEquals("Порт 443 (HTTPS)", имяПорта(443))
        assertEquals("Порт 2222", имяПорта(2222))
    }

    @Test
    fun `имя службы дополняется суффиксом service`() {
        assertEquals("nginx.service", нормализоватьСлужбу("nginx"))
        assertEquals("nginx.service", нормализоватьСлужбу(" nginx "))
        assertEquals("redis-server.service", нормализоватьСлужбу("redis-server.service"))
    }

    @Test
    fun `ssh и ssh service считаются одной службой`() {
        assertEquals(нормализоватьСлужбу("ssh"), нормализоватьСлужбу("ssh.service"))
        assertEquals(нормализоватьСлужбу("cron"), нормализоватьСлужбу("cron.service"))
        assertEquals(нормализоватьСлужбу("SSH"), нормализоватьСлужбу(" ssh.SERVICE "))
        // Юниты с другим суффиксом не переделываются в несуществующие «.socket.service».
        assertEquals("nginx.socket", нормализоватьСлужбу("nginx.socket"))
        assertEquals("postgresql@14-main.service", нормализоватьСлужбу("postgresql@14-main"))
    }

    @Test
    fun `повторное добавление сайта не создаёт дубль`() {
        val сайты = mutableListOf<Pair<String, String>>()
        assertTrue(добавитьБезДубля(сайты, "example.com" to "https://example.com", ключ = { ключАдреса(it.second) }))
        assertFalse(добавитьБезДубля(сайты, "example.com" to "https://example.com/", ключ = { ключАдреса(it.second) }))
        assertFalse(добавитьБезДубля(сайты, "Example.com" to "https://EXAMPLE.com", ключ = { ключАдреса(it.second) }))
        assertTrue(добавитьБезДубля(сайты, "192.168.0.2" to "http://192.168.0.2", ключ = { ключАдреса(it.second) }))
        assertEquals(2, сайты.size)
    }

    @Test
    fun `повторное добавление порта не создаёт дубль`() {
        val порты = mutableListOf<Int>()
        assertTrue(добавитьБезДубля(порты, 8080, ключ = { it.toString() }))
        assertFalse(добавитьБезДубля(порты, 8080, ключ = { it.toString() }))
        assertTrue(добавитьБезДубля(порты, 443, ключ = { it.toString() }))
        assertEquals(listOf(8080, 443), порты)
    }

    @Test
    fun `адреса сайтов сравниваются без учёта схемы-регистра и хвостового слэша`() {
        assertEquals(ключАдреса("https://Example.com/"), ключАдреса(" https://example.com "))
        assertEquals("https://example.com", ключАдреса("https://example.com/"))
        assertTrue(ключАдреса("https://blembula.pp.ua") != ключАдреса("https://zsulink.pp.ua"))
    }

    @Test
    fun `повторное добавление службы не создаёт дубль`() {
        val список = mutableListOf<String>()
        assertTrue(добавитьСлужбу(список, "ssh"))
        assertFalse(добавитьСлужбу(список, "ssh.service"))
        assertFalse(добавитьСлужбу(список, " SSH.service "))
        assertTrue(добавитьСлужбу(список, "cron"))
        assertFalse(добавитьСлужбу(список, "   "))
        assertEquals(listOf("ssh.service", "cron.service"), список)
    }

    @Test
    fun `старые настройки без суффикса приводятся к единому виду`() {
        assertEquals(listOf("ssh.service", "cron.service"), привестиСписокСлужб(listOf("ssh", "cron")))
        assertEquals(listOf("ssh.service", "nginx.service"), привестиСписокСлужб(listOf("ssh", "ssh.service", "nginx")))
        assertEquals(emptyList<String>(), привестиСписокСлужб(listOf("", "  ")))
    }
}
