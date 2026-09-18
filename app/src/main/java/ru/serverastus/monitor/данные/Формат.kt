package ru.serverastus.monitor.данные

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val ЧАСЫ = DateTimeFormatter.ofPattern("HH:mm:ss")
private val ЧАСЫ_ДАТА = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss")
private val ДАТА_ВРЕМЯ = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

/** Одно и то же число всегда печатается одинаково — без зависимости от локали. */
private fun одно(значение: Double): String = String.format(Locale.ROOT, "%.1f", значение)

private fun два(значение: Double): String = String.format(Locale.ROOT, "%.2f", значение)

/** Байты в удобочитаемый вид (КиБ, МиБ, ГиБ) — как format_bytes в metrics.py. */
fun форматБайт(значение: Long?): String {
    if (значение == null) return "—"
    var размер = значение.toDouble()
    for (единица in listOf("Б", "КиБ", "МиБ", "ГиБ", "ТиБ", "ПиБ")) {
        if (abs(размер) < 1024.0 || единица == "ПиБ") {
            val подпись = л(единица)
            return if (единица == "Б") "${размер.toInt()} $подпись" else "${одно(размер)} $подпись"
        }
        размер /= 1024.0
    }
    return "${одно(размер)} ${л("ПиБ")}"
}

/** Байты в секунду. */
fun форматСкорости(значение: Double?): String =
    if (значение == null) "—" else "${форматБайт(значение.toLong())}${л("/с")}"

/** Время работы сервера в виде «3 д 4 ч 12 мин» — как format_uptime в metrics.py. */
fun форматАптайм(секунды: Long?): String {
    if (секунды == null || секунды <= 0) return "—"
    var остаток = секунды
    val дни = остаток / 86400
    остаток %= 86400
    val часы = остаток / 3600
    остаток %= 3600
    val минуты = остаток / 60
    val части = mutableListOf<String>()
    if (дни > 0) части.add(л("{1} д", дни))
    if (часы > 0 || дни > 0) части.add(л("{1} ч", часы))
    части.add(л("{1} мин", минуты))
    return части.joinToString(" ")
}

fun времяТекст(отметка: Long): String =
    Instant.ofEpochSecond(отметка).atZone(ZoneId.systemDefault()).format(ЧАСЫ)

fun времяДата(отметка: Long): String =
    Instant.ofEpochSecond(отметка).atZone(ZoneId.systemDefault()).format(ЧАСЫ_ДАТА)

fun полнаяДата(отметка: Long): String =
    Instant.ofEpochSecond(отметка).atZone(ZoneId.systemDefault()).format(ДАТА_ВРЕМЯ)

/** Проценты: «42%» или «42.5%» для дробных значений. */
fun форматПроцент(значение: Double?): String {
    if (значение == null) return "—"
    return if (abs(значение - значение.toInt()) < 0.05) "${значение.toInt()}%" else "${одно(значение)}%"
}

/** Задержка ответа: «12 мс» или «1.40 с». */
fun форматЗадержка(мс: Double?): String {
    if (мс == null) return "—"
    return if (мс < 1000) л("{1} мс", мс.toInt()) else л("{1} с", два(мс / 1000.0))
}

fun форматЧисло(значение: Double?): String {
    if (значение == null) return "—"
    return два(значение)
}

/** Целое число с разделителями разрядов: «1 118 232». */
fun форматСчётчик(значение: Long?): String {
    if (значение == null) return "—"
    val цифры = abs(значение).toString()
    val итог = StringBuilder()
    for ((индекс, знак) in цифры.withIndex()) {
        if (индекс > 0 && (цифры.length - индекс) % 3 == 0) итог.append(' ')
        итог.append(знак)
    }
    return if (значение < 0) "-$итог" else итог.toString()
}

/**
 * Число пакетов вместе со словом «пакет» в нужной форме: «1 пакет», «272
 * пакета», «5 пакетов». В русском и украинском форма зависит от последних
 * цифр, поэтому подставляется уже готовое слово (в английском форм две).
 * Нужна там, где число стоит прямо перед существительным, а не после
 * двоеточия.
 */
fun пакетовПрописью(число: Long): String {
    val сотни = число % 100
    val единицы = число % 10
    val количество = форматСчётчик(число)
    return when {
        сотни in 11..14 -> л("{1} пакетов", количество)
        единицы == 1L -> л("{1} пакет", количество)
        единицы in 2..4 -> л("{1} пакета", количество)
        else -> л("{1} пакетов", количество)
    }
}
