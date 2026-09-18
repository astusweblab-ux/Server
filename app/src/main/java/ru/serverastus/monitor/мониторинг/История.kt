package ru.serverastus.monitor.мониторинг

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import ru.serverastus.monitor.данные.Метрики
import ru.serverastus.monitor.данные.РезультатПроверки
import ru.serverastus.monitor.данные.СобытиеЖурнала
import ru.serverastus.monitor.данные.ТочкаГрафика
import ru.serverastus.monitor.сеть.округлить
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * История измерений в SQLite — та же схема, что в `storage.py` настольной версии,
 * поэтому файл истории совместим по структуре таблиц и колонок.
 */
class История(
    context: Context,
    имяФайла: String = "сервер_история.db",
) : SQLiteOpenHelper(context.applicationContext, имяФайла, null, ВЕРСИЯ_БАЗЫ) {

    private val замок = ReentrantLock()
    private val контекст: Context = context.applicationContext
    private val имяБазы: String = имяФайла

    init {
        // Журнал WAL, как в настольной версии: запись не блокирует чтение графиков.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(бд: SQLiteDatabase) {
        бд.execSQL(
            """
            CREATE TABLE IF NOT EXISTS метрики (
                ид INTEGER PRIMARY KEY AUTOINCREMENT,
                время INTEGER NOT NULL,
                цп REAL,
                озу_занято INTEGER,
                озу_всего INTEGER,
                диск_занято INTEGER,
                диск_всего INTEGER,
                сеть_отправлено INTEGER,
                сеть_получено INTEGER,
                нагрузка1 REAL,
                процессов INTEGER,
                аптайм INTEGER
            )
            """.trimIndent(),
        )
        бд.execSQL("CREATE INDEX IF NOT EXISTS idx_метрики_время ON метрики (время)")
        бд.execSQL(
            """
            CREATE TABLE IF NOT EXISTS проверки (
                ид INTEGER PRIMARY KEY AUTOINCREMENT,
                время INTEGER NOT NULL,
                имя TEXT NOT NULL,
                вид TEXT NOT NULL,
                адрес TEXT,
                доступно INTEGER NOT NULL,
                код INTEGER,
                время_мс REAL,
                ssl_дней INTEGER,
                ошибка TEXT
            )
            """.trimIndent(),
        )
        бд.execSQL("CREATE INDEX IF NOT EXISTS idx_проверки_имя_время ON проверки (имя, время)")
        бд.execSQL(
            """
            CREATE TABLE IF NOT EXISTS события (
                ид INTEGER PRIMARY KEY AUTOINCREMENT,
                время INTEGER NOT NULL,
                уровень TEXT NOT NULL,
                цель TEXT,
                сообщение TEXT NOT NULL
            )
            """.trimIndent(),
        )
        бд.execSQL("CREATE INDEX IF NOT EXISTS idx_события_время ON события (время)")
    }

    override fun onUpgrade(бд: SQLiteDatabase, старая: Int, новая: Int) {
        // Структура пока одна — при изменении схемы добавлять миграции здесь.
    }

    // --- Метрики сервера -------------------------------------------------

    fun добавитьМетрики(метрики: Метрики) {
        val значения = ContentValues().apply {
            put("время", if (метрики.время > 0) метрики.время else System.currentTimeMillis() / 1000)
            put("цп", метрики.цп)
            put("озу_занято", метрики.озуЗанято)
            put("озу_всего", метрики.озуВсего)
            put("диск_занято", метрики.дискЗанято)
            put("диск_всего", метрики.дискВсего)
            put("сеть_отправлено", метрики.сетьОтправлено)
            put("сеть_получено", метрики.сетьПолучено)
            put("нагрузка1", метрики.нагрузка1)
            put("процессов", метрики.процессов)
            put("аптайм", метрики.аптайм)
        }
        замок.withLock { writableDatabase.insert("метрики", null, значения) }
    }

    /**
     * Точки графика за окно наблюдения: ЦП, ОЗУ и заполнение основного диска.
     * Список прореживается, чтобы на экран уходило не больше [предел] точек.
     */
    fun историяГрафика(с: Long, предел: Int = 4000, максимумТочек: Int = 360): List<ТочкаГрафика> {
        val строки = mutableListOf<ТочкаГрафика>()
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT время, цп, озу_занято, озу_всего, диск_занято, диск_всего " +
                        "FROM метрики WHERE время >= ? ORDER BY время ASC LIMIT ?",
                    arrayOf(с.toString(), предел.toString()),
                )
                .use { курсор ->
                    while (курсор.moveToNext()) {
                        val занято = еслиЕсть(курсор, "озу_занято")
                        val всего = еслиЕсть(курсор, "озу_всего")
                        val озу = if (всего != null && всего > 0 && занято != null) {
                            округлить(100.0 * занято / всего, 1)
                        } else {
                            null
                        }
                        val дискЗанято = еслиЕсть(курсор, "диск_занято")
                        val дискВсего = еслиЕсть(курсор, "диск_всего")
                        val диск = if (дискВсего != null && дискВсего > 0 && дискЗанято != null) {
                            округлить(100.0 * дискЗанято / дискВсего, 1)
                        } else {
                            null
                        }
                        строки.add(
                            ТочкаГрафика(
                                время = курсор.getLong(0),
                                цп = еслиДробное(курсор, "цп"),
                                озу = озу,
                                диск = диск,
                            ),
                        )
                    }
                }
        }
        return проредить(строки, максимумТочек)
    }

    /**
     * Самая свежая запись метрик — для виджета на рабочем столе: он не опрашивает
     * сервер сам, а показывает то, что уже успел собрать движок или служба.
     */
    fun последниеМетрики(): Метрики? {
        var итог: Метрики? = null
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT время, цп, озу_занято, озу_всего, диск_занято, диск_всего, " +
                        "нагрузка1, процессов, аптайм FROM метрики ORDER BY время DESC LIMIT 1",
                    null,
                )
                .use { курсор ->
                    if (курсор.moveToFirst()) {
                        val озуЗанято = еслиЕсть(курсор, "озу_занято")
                        val озуВсего = еслиЕсть(курсор, "озу_всего")
                        val дискЗанято = еслиЕсть(курсор, "диск_занято")
                        val дискВсего = еслиЕсть(курсор, "диск_всего")
                        итог = Метрики(
                            время = курсор.getLong(0),
                            цп = еслиДробное(курсор, "цп"),
                            озуЗанято = озуЗанято,
                            озуВсего = озуВсего,
                            озуПроцент = процентИли(озуЗанято, озуВсего),
                            дискЗанято = дискЗанято,
                            дискВсего = дискВсего,
                            дискПроцент = процентИли(дискЗанято, дискВсего),
                            нагрузка1 = еслиДробное(курсор, "нагрузка1"),
                            процессов = еслиЦелое(курсор, "процессов"),
                            аптайм = еслиЕсть(курсор, "аптайм"),
                        )
                    }
                }
        }
        return итог
    }

    /** Все записи метрик — для выгрузки в CSV (без прореживания). */
    fun метрикиЗаписи(предел: Int = 200000): List<Метрики> {
        val строки = mutableListOf<Метрики>()
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT время, цп, озу_занято, озу_всего, диск_занято, диск_всего, " +
                        "нагрузка1, процессов, аптайм FROM метрики ORDER BY время ASC LIMIT ?",
                    arrayOf(предел.toString()),
                )
                .use { курсор ->
                    while (курсор.moveToNext()) {
                        строки.add(
                            Метрики(
                                время = курсор.getLong(0),
                                цп = еслиДробное(курсор, "цп"),
                                озуЗанято = еслиЕсть(курсор, "озу_занято"),
                                озуВсего = еслиЕсть(курсор, "озу_всего"),
                                дискЗанято = еслиЕсть(курсор, "диск_занято"),
                                дискВсего = еслиЕсть(курсор, "диск_всего"),
                                нагрузка1 = еслиДробное(курсор, "нагрузка1"),
                                процессов = еслиЦелое(курсор, "процессов"),
                                аптайм = еслиЕсть(курсор, "аптайм"),
                            ),
                        )
                    }
                }
        }
        return строки
    }

    // --- Проверки сайтов и портов ----------------------------------------

    fun добавитьПроверку(результат: РезультатПроверки) {
        val значения = ContentValues().apply {
            put(
                "время",
                if (результат.время > 0) результат.время else System.currentTimeMillis() / 1000,
            )
            put("имя", результат.имя)
            put("вид", результат.вид)
            put("адрес", результат.адрес)
            put("доступно", if (результат.доступно) 1 else 0)
            put("код", результат.код)
            put("время_мс", результат.времяМс)
            put("ssl_дней", результат.sslДней)
            put("ошибка", результат.ошибка)
        }
        замок.withLock { writableDatabase.insert("проверки", null, значения) }
    }

    /** Последние проверки цели — для полосок доступности на карточке. */
    fun историяПроверок(имя: String, с: Long, предел: Int = 240): List<РезультатПроверки> {
        val строки = mutableListOf<РезультатПроверки>()
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT время, доступно, время_мс FROM проверки " +
                        "WHERE имя = ? AND время >= ? ORDER BY время ASC LIMIT ?",
                    arrayOf(имя, с.toString(), предел.toString()),
                )
                .use { курсор ->
                    while (курсор.moveToNext()) {
                        строки.add(
                            РезультатПроверки(
                                время = курсор.getLong(0),
                                доступно = курсор.getInt(1) != 0,
                                времяМс = еслиДробное(курсор, "время_мс"),
                            ),
                        )
                    }
                }
        }
        return строки
    }

    /** Процент доступности цели за период и число проверок. */
    fun доступность(имя: String, с: Long): Pair<Double?, Int> {
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT COUNT(*) AS всего, SUM(доступно) AS удачно " +
                        "FROM проверки WHERE имя = ? AND время >= ?",
                    arrayOf(имя, с.toString()),
                )
                .use { курсор ->
                    if (!курсор.moveToFirst()) return Pair(null, 0)
                    val всего = курсор.getInt(0)
                    if (всего == 0) return Pair(null, 0)
                    val удачно = if (курсор.isNull(1)) 0 else курсор.getInt(1)
                    return Pair(округлить(100.0 * удачно / всего, 2), всего)
                }
        }
    }

    /** Среднее и максимальное время ответа цели за период. */
    fun статистикаЗадержек(имя: String, с: Long): Pair<Double?, Double?> {
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT AVG(время_мс) AS среднее, MAX(время_мс) AS максимум " +
                        "FROM проверки WHERE имя = ? AND время >= ? AND доступно = 1",
                    arrayOf(имя, с.toString()),
                )
                .use { курсор ->
                    if (!курсор.moveToFirst()) return Pair(null, null)
                    val среднее = еслиДробное(курсор, "среднее")
                    val максимум = еслиДробное(курсор, "максимум")
                    return Pair(
                        среднее?.let { округлить(it, 1) },
                        максимум?.let { округлить(it, 1) },
                    )
                }
        }
    }

    /** Все имена, по которым есть история проверок. */
    fun имена(): List<String> {
        val итог = mutableListOf<String>()
        замок.withLock {
            readableDatabase.rawQuery("SELECT DISTINCT имя FROM проверки", null).use { курсор ->
                while (курсор.moveToNext()) итог.add(курсор.getString(0) ?: "")
            }
        }
        return итог
    }

    fun всеПроверки(предел: Int = 200000): List<РезультатПроверки> {
        val строки = mutableListOf<РезультатПроверки>()
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT время, имя, вид, адрес, доступно, код, время_мс, ssl_дней, ошибка " +
                        "FROM проверки ORDER BY время ASC LIMIT ?",
                    arrayOf(предел.toString()),
                )
                .use { курсор ->
                    while (курсор.moveToNext()) {
                        строки.add(проверкаИзКурсора(курсор))
                    }
                }
        }
        return строки
    }

    /** Последняя проверка по каждому имени сайта/порта — для виджета на рабочем столе. */
    fun последниеПроверки(): List<РезультатПроверки> {
        val строки = mutableListOf<РезультатПроверки>()
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT время, имя, вид, адрес, доступно, код, время_мс, ssl_дней, ошибка " +
                        "FROM проверки WHERE ид IN (SELECT MAX(ид) FROM проверки GROUP BY имя)",
                    null,
                )
                .use { курсор ->
                    while (курсор.moveToNext()) строки.add(проверкаИзКурсора(курсор))
                }
        }
        return строки
    }

    private fun проверкаИзКурсора(курсор: Cursor) = РезультатПроверки(
        время = курсор.getLong(0),
        имя = курсор.getString(1) ?: "",
        вид = курсор.getString(2) ?: "http",
        адрес = курсор.getString(3) ?: "",
        доступно = курсор.getInt(4) != 0,
        код = еслиЦелое(курсор, "код"),
        времяМс = еслиДробное(курсор, "время_мс"),
        sslДней = еслиЦелое(курсор, "ssl_дней"),
        ошибка = if (курсор.isNull(8)) null else курсор.getString(8),
    )

    // --- События ---------------------------------------------------------

    fun добавитьСобытие(уровень: String, цель: String, сообщение: String) {
        val значения = ContentValues().apply {
            put("время", System.currentTimeMillis() / 1000)
            put("уровень", уровень)
            put("цель", цель)
            put("сообщение", сообщение)
        }
        замок.withLock { writableDatabase.insert("события", null, значения) }
    }

    fun события(предел: Int = 100): List<СобытиеЖурнала> {
        val строки = mutableListOf<СобытиеЖурнала>()
        замок.withLock {
            readableDatabase
                .rawQuery(
                    "SELECT время, уровень, цель, сообщение FROM события " +
                        "ORDER BY время DESC, ид DESC LIMIT ?",
                    arrayOf(предел.toString()),
                )
                .use { курсор ->
                    while (курсор.moveToNext()) {
                        строки.add(
                            СобытиеЖурнала(
                                время = курсор.getLong(0),
                                уровень = курсор.getString(1) ?: "сведения",
                                цель = курсор.getString(2) ?: "",
                                сообщение = курсор.getString(3) ?: "",
                            ),
                        )
                    }
                }
        }
        return строки
    }

    // --- Обслуживание ----------------------------------------------------

    /** Полностью удаляет журнал событий, возвращает число удалённых записей. */
    fun обнулитьСобытия(): Int {
        var удалено = 0
        замок.withLock {
            удалено = writableDatabase.delete("события", null, null)
        }
        return удалено
    }

    /** Удаляет устаревшие записи, возвращает число удалённых строк. */
    fun очистить(дней: Double): Int {
        val граница = System.currentTimeMillis() / 1000 - (дней * 86400).toLong()
        var удалено = 0
        замок.withLock {
            val бд = writableDatabase
            for (таблица in listOf("метрики", "проверки", "события")) {
                удалено += бд.delete(таблица, "время < ?", arrayOf(граница.toString()))
            }
        }
        return удалено
    }

    /** Размер базы вместе с журналом WAL — как `size_bytes` в настольной версии. */
    fun размерБайт(): Long {
        val основной = контекст.getDatabasePath(имяБазы)
        var всего = 0L
        for (суффикс in listOf("", "-wal", "-shm")) {
            val файл = File(основной.absolutePath + суффикс)
            if (файл.exists()) всего += файл.length()
        }
        return всего
    }

    /**
     * Освобождает файл истории. Нужен при переключении сервера: у каждого сервера
     * своя база, и прежнюю держать открытой незачем.
     */
    fun закрыть() {
        try {
            close()
        } catch (_: Exception) {
            // база уже закрыта или недоступна — освобождать нечего
        }
    }

    companion object {
        const val ВЕРСИЯ_БАЗЫ = 1
    }
}

private fun еслиЕсть(курсор: Cursor, колонка: String): Long? {
    val индекс = курсор.getColumnIndex(колонка)
    if (индекс < 0 || курсор.isNull(индекс)) return null
    return курсор.getLong(индекс)
}

private fun еслиЦелое(курсор: Cursor, колонка: String): Int? {
    val индекс = курсор.getColumnIndex(колонка)
    if (индекс < 0 || курсор.isNull(индекс)) return null
    return курсор.getInt(индекс)
}

private fun еслиДробное(курсор: Cursor, колонка: String): Double? {
    val индекс = курсор.getColumnIndex(колонка)
    if (индекс < 0 || курсор.isNull(индекс)) return null
    return курсор.getDouble(индекс)
}

/** Процент занятости из «занято/всего» — так же, как считает историю графика. */
private fun процентИли(занято: Long?, всего: Long?): Double? {
    if (занято == null || всего == null || всего <= 0) return null
    return округлить(100.0 * занято / всего, 1)
}

/** Прореживает длинный список, сохраняя форму графика (первая/последняя точка на месте). */
fun <T> проредить(строки: List<T>, предел: Int): List<T> {
    if (предел <= 0 || строки.size <= предел) return строки.toList()
    val шаг = строки.size.toDouble() / предел
    val итог = mutableListOf<T>()
    var индекс = 0.0
    while (индекс.toInt() < строки.size) {
        итог.add(строки[индекс.toInt()])
        индекс += шаг
    }
    if (итог.last() !== строки.last()) итог.add(строки.last())
    return итог
}
