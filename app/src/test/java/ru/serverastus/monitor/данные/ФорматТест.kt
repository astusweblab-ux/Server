package ru.serverastus.monitor.данные

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Форматирование должно совпадать с настольной версией
 * (`server_monitor/metrics.py`: `format_bytes`, `format_uptime`).
 */
class ФорматТест {

    @Before
    fun фиксируемРусскийЯзык() {
        Локаль.подменитьЯзык("ru")
    }

    @After
    fun возвращаемСистемныйЯзык() {
        Локаль.подменитьЯзык(null)
    }

    @Test
    fun `байты печатаются как в эталоне`() {
        assertEquals("—", форматБайт(null))
        assertEquals("0 Б", форматБайт(0))
        assertEquals("512 Б", форматБайт(512))
        assertEquals("1023 Б", форматБайт(1023))
        assertEquals("1.0 КиБ", форматБайт(1024))
        assertEquals("1.5 КиБ", форматБайт(1536))
        assertEquals("1.0 МиБ", форматБайт(1048576))
        assertEquals("15.5 ГиБ", форматБайт(16674275328))
        assertEquals("15.7 ГиБ", форматБайт(16843571200))
        assertEquals("232.6 ГиБ", форматБайт(249792131072))
        assertEquals("1.0 ГиБ", форматБайт(1124995072))
        assertEquals("7.6 МиБ", форматБайт(8015872))
        assertEquals("3.0 ТиБ", форматБайт(3298534883328))
    }

    @Test
    fun `аптайм печатается как в эталоне`() {
        assertEquals("—", форматАптайм(null))
        assertEquals("—", форматАптайм(0))
        assertEquals("0 мин", форматАптайм(59))
        assertEquals("1 мин", форматАптайм(60))
        assertEquals("1 ч 0 мин", форматАптайм(3600))
        assertEquals("1 ч 1 мин", форматАптайм(3661))
        assertEquals("1 д 0 ч 0 мин", форматАптайм(86400))
        assertEquals("1 д 23 ч 24 мин", форматАптайм(170676))
    }

    @Test
    fun `скорость и задержка не ломаются на пустых значениях`() {
        assertEquals("—", форматСкорости(null))
        assertEquals("1.0 КиБ/с", форматСкорости(1024.0))
        assertEquals("—", форматЗадержка(null))
        assertEquals("12 мс", форматЗадержка(12.0))
        assertEquals("1.40 с", форматЗадержка(1400.0))
        assertEquals("—", форматПроцент(null))
        // Целые проценты печатаются без дробной части, дробные — с одним знаком.
        assertEquals("8%", форматПроцент(8.0))
        assertEquals("8.3%", форматПроцент(8.3))
        assertEquals("—", форматЧисло(null))
        assertEquals("0.05", форматЧисло(0.05))
    }

    @Test
    fun `количество пакетов согласуется с числом`() {
        assertEquals("1 пакет", пакетовПрописью(1))
        assertEquals("2 пакета", пакетовПрописью(2))
        assertEquals("4 пакета", пакетовПрописью(4))
        assertEquals("5 пакетов", пакетовПрописью(5))
        assertEquals("11 пакетов", пакетовПрописью(11))
        assertEquals("21 пакет", пакетовПрописью(21))
        assertEquals("272 пакета", пакетовПрописью(272))
        assertEquals("1 113 пакетов", пакетовПрописью(1113))
    }
}
