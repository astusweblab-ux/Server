package ru.serverastus.monitor.сеть

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import ru.serverastus.monitor.данные.Локаль

/**
 * Проверки адресов и переводы ошибок должны совпадать с настольной версией
 * (`server_monitor/checks.py`): эталонные значения сняты прогоном её функций.
 */
class ПроверкиТест {

    @Before
    fun фиксируемРусскийЯзык() {
        Локаль.подменитьЯзык("ru")
    }

    @After
    fun возвращаемСистемныйЯзык() {
        Локаль.подменитьЯзык(null)
    }

    @Test
    fun `адрес приводится к латинице как в эталоне`() {
        assertEquals("https://example.com/", нормализоватьАдрес("https://example.com"))

        assertEquals(
            "https://xn--e1afmkfd.xn--p1ai/%D0%BA%D0%B0%D1%82%D0%B0%D0%BB%D0%BE%D0%B3/%D1%82%D0%BE%D0%B2%D0%B0%D1%80?id=5",
            нормализоватьАдрес("https://пример.рф/каталог/товар?id=5"),
        )

        assertEquals(
            "http://example.com/%D0%BF%D1%83%D1%82%D1%8C/%D1%81%D1%82%D1%80%D0%B0%D0%BD%D0%B8%D1%86%D0%B0?%D0%B8%D0%BC%D1%8F=%D0%B7%D0%BD%D0%B0%D1%87%D0%B5%D0%BD%D0%B8%D0%B5&x=1",
            нормализоватьАдрес("http://example.com/путь/страница?имя=значение&x=1"),
        )

        assertEquals("https://example.com:8443/", нормализоватьАдрес("https://example.com:8443/"))
        assertEquals("https://example.com/a%20b/c", нормализоватьАдрес("https://example.com/a b/c"))
        assertEquals(
            "https://example.com/%D0%BF%D1%83%D1%82%D1%8C#%D1%8F%D0%BA%D0%BE%D1%80%D1%8C",
            нормализоватьАдрес("https://example.com/путь#якорь"),
        )
        assertEquals(
            "http://2001:db8::1:8080/%D0%BF%D1%83%D1%82%D1%8C",
            нормализоватьАдрес("http://[2001:db8::1]:8080/путь"),
        )

        // Уже закодированный адрес повторно не портится.
        assertEquals(
            "https://example.com/%D1%83%D0%B6%D0%B5%20%D0%B7%D0%B0%D0%BA%D0%BE%D0%B4%D0%B8%D1%80%D0%BE%D0%B2%D0%B0%D0%BD%D0%BE",
            нормализоватьАдрес("https://example.com/уже%20закодировано"),
        )
    }

    @Test
    fun `адрес без схемы остаётся как есть`() {
        assertEquals("example.com", нормализоватьАдрес("example.com"))
        assertEquals("", нормализоватьАдрес(""))
        assertEquals("", нормализоватьАдрес(null))
    }

    @Test
    fun `коды ответа расшифровываются`() {
        assertEquals(" (требуется авторизация)", описаниеКода(401))
        assertEquals(" (доступ запрещён)", описаниеКода(403))
        assertEquals(" (страница не найдена)", описаниеКода(404))
        assertEquals(" (слишком много запросов, включилось ограничение)", описаниеКода(429))
        assertEquals(
            " (внутренняя ошибка сервера (ошибка в коде сайта))",
            описаниеКода(500),
        )
        assertEquals(" (служба недоступна: сервер перегружен или на обслуживании)", описаниеКода(503))
        assertEquals(" (Cloudflare: веб-сервер не отвечает)", описаниеКода(521))
        assertEquals("", описаниеКода(200))
        assertEquals("", описаниеКода(599))
        assertEquals("", описаниеКода(null))
    }

    @Test
    fun `сетевые ошибки переводятся`() {
        assertEquals("Соединение отклонено (сервер не слушает порт)", перевестиОшибку("Connection refused"))
        assertEquals("Соединение отклонено (сервер не слушает порт)", перевестиОшибку("connection refused"))
        assertEquals("Не удалось определить адрес узла (проверьте DNS)", перевестиОшибку("Name or service not known"))
        assertEquals("Не удалось определить адрес узла", перевестиОшибку("getaddrinfo failed"))
        assertEquals("Превышено время ожидания ответа", перевестиОшибку("timed out"))
        // «timed out» проверяется раньше, чем «The read operation timed out» —
        // порядок образцов взят из настольной версии и менять его нельзя.
        assertEquals("Превышено время ожидания ответа", перевестиОшибку("The read operation timed out"))
        assertEquals("Узел недоступен (нет маршрута)", перевестиОшибку("No route to host"))
        assertEquals("Сеть недоступна", перевестиОшибку("Network is unreachable"))
        assertEquals("Соединение разорвано удалённой стороной", перевестиОшибку("Connection reset by peer"))
        assertEquals("Ошибка проверки SSL-сертификата", перевестиОшибку("certificate verify failed"))
        assertEquals("Ошибка соединения: Что-то своё", перевестиОшибку("Что-то своё"))
        assertEquals("Ошибка соединения: ", перевестиОшибку(null))
    }

    @Test
    fun `служебные причины ответа отбрасываются`() {
        assertEquals("", чистаяПричина(""))
        assertEquals("", чистаяПричина("  "))
        assertEquals("", чистаяПричина("None"))
        assertEquals("", чистаяПричина("<none>"))
        assertEquals("", чистаяПричина("unknown"))
        assertEquals("", чистаяПричина("null"))
        assertEquals("", чистаяПричина("-"))
        assertEquals("", чистаяПричина(null))
        assertEquals("Forbidden", чистаяПричина(" Forbidden "))
    }
}
