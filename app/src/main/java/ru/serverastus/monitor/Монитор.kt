package ru.serverastus.monitor

import android.app.Application
import android.os.Build
import ru.serverastus.monitor.данные.Настройки
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.полнаяДата
import ru.serverastus.monitor.мониторинг.Движок
import ru.serverastus.monitor.мониторинг.История
import ru.serverastus.monitor.мониторинг.Оповещения
import java.io.File

/**
 * Точка входа приложения: держит общие для интерфейса и службы настройки,
 * историю, оповещения и движок опроса.
 *
 * Серверов может быть несколько. Все они живут в [Настройки], а движок всегда
 * работает с активным профилем; история и оповещения обращаются к истории
 * активного сервера через движок, поэтому данные серверов не смешиваются.
 */
class Монитор : Application() {

    lateinit var настройки: Настройки
        private set
    lateinit var оповещения: Оповещения
        private set
    lateinit var движок: Движок
        private set

    /** История активного сервера. При переключении сервера меняется вместе с движком. */
    val история: История get() = движок.история

    private val журналДоСтарта = mutableListOf<String>()

    override fun onCreate() {
        super.onCreate()
        настройки = Настройки(this).also { it.загрузить() }
        Оповещения.создатьКаналы(this)
        оповещения = Оповещения(this, настройки, { движок.история }) { сообщение -> журнал(сообщение) }
        движок = Движок(настройки, оповещения) { имя -> История(this, имя) }

        журналДоСтарта.forEach { движок.записатьВЖурнал(it) }
        журналДоСтарта.clear()

        установитьЛовушкуОшибок()
        движок.записатьВЖурнал(
            л(
                "Приложение запущено (Android {1}, {2})",
                Build.VERSION.RELEASE,
                Build.MODEL,
            ),
        )
    }

    /** Переключает приложение на другой профиль сервера и перезапускает опрос. */
    fun переключитьСервер(ид: String) = движок.переключитьСервер(ид)

    /** Перечитывает активный профиль — после правки настроек или удаления сервера. */
    fun обновитьСервер() = движок.обновитьСервер()

    /** Пишет строку в техжурнал приложения (в памяти и в файле «журнал.log»). */
    fun журнал(сообщение: String) {
        if (::движок.isInitialized) движок.записатьВЖурнал(сообщение) else журналДоСтарта.add(сообщение)
    }

    private fun установитьЛовушкуОшибок() {
        val прежний = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { поток, ошибка ->
            записатьОшибку(поток, ошибка)
            прежний?.uncaughtException(поток, ошибка)
        }
    }

    private fun записатьОшибку(поток: Thread, ошибка: Throwable) {
        try {
            val файл = File(настройки.каталог, "ошибки.log")
            val текст = buildString {
                append("${полнаяДата(System.currentTimeMillis() / 1000)} ")
                append("поток «${поток.name}»: ${ошибка::class.java.name}: ${ошибка.message}\n")
                append(ошибка.stackTraceToString())
                append("\n")
            }
            файл.appendText(текст)
        } catch (_: Exception) {
            // записать причину падения не удалось — молчим, приложение всё равно падает
        }
    }
}
