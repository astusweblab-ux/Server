package ru.serverastus.monitor.мониторинг

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONObject
import ru.serverastus.monitor.MainActivity
import ru.serverastus.monitor.R
import ru.serverastus.monitor.данные.Локаль
import ru.serverastus.monitor.данные.Настройки
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.уровеньСобытия
import ru.serverastus.monitor.сеть.ПОЛЬЗОВАТЕЛЬ_АГЕНТ
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Значки уровней — как ICONS в notifier.py. */
val ЗНАЧКИ_УРОВНЕЙ = mapOf(
    "сбой" to "🔴",
    "восстановление" to "🟢",
    "предупреждение" to "🟡",
    "сведения" to "ℹ️",
)

/**
 * Собирает текст оповещения. Telegram понимает HTML (`<b>`), а Discord и
 * большинство вебхуков — обычный Markdown (`**`).
 *
 * Цель и сообщение записаны в журнал на своём языке, поэтому перед отправкой
 * они переводятся на текущий язык интерфейса (см. `Локаль.перевестиГотовый`).
 */
fun текстОповещения(уровень: String, цель: String, сообщение: String, markdown: Boolean = false): String {
    val значок = ЗНАЧКИ_УРОВНЕЙ[уровень] ?: "ℹ️"
    val открыть = if (markdown) "**" else "<b>"
    val закрыть = if (markdown) "**" else "</b>"
    val место = Локаль.перевестиГотовый(цель)
    val текст = Локаль.перевестиГотовый(сообщение)
    return "$значок $открыть${уровеньСобытия(уровень)}$закрыть\n$место\n$текст"
}

/** Отправляет сообщение в Telegram. При ошибке бросает исключение. */
fun отправитьТелеграм(токен: String, чат: String, текст: String) {
    val адрес = "https://api.telegram.org/bot$токен/sendMessage"
    val тело = listOf(
        "chat_id" to чат,
        "text" to текст,
        "parse_mode" to "HTML",
    ).joinToString("&") { (ключ, значение) ->
        "${URLEncoder.encode(ключ, "UTF-8")}=${URLEncoder.encode(значение, "UTF-8")}"
    }.toByteArray(Charsets.UTF_8)
    запрос(адрес, тело, "application/x-www-form-urlencoded; charset=utf-8")
}

/**
 * Отправляет JSON на вебхук. Поле «content» с готовым текстом в Markdown —
 * формат, который понимает Discord; без него Discord отвечает 400.
 */
fun отправитьВебхук(адрес: String, данные: JSONObject, текст: String) {
    val полезная = JSONObject(данные.toString())
    полезная.put("content", текст)
    запрос(адрес, полезная.toString().toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
}

private fun запрос(адрес: String, тело: ByteArray, типСодержимого: String) {
    val соединение = (URL(адрес).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = 15000
        readTimeout = 15000
        setRequestProperty("Content-Type", типСодержимого)
        // Свой User-Agent обязателен: Discord (через Cloudflare) отвечает 403
        // на стандартный агент ещё до проверки самого запроса.
        setRequestProperty("User-Agent", ПОЛЬЗОВАТЕЛЬ_АГЕНТ)
    }
    try {
        соединение.outputStream.use { поток -> поток.write(тело) }
        val код = соединение.responseCode
        if (код !in 200..299) {
            val ответ = try {
                соединение.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(200) ?: ""
            } catch (_: Exception) {
                ""
            }
            throw IllegalStateException("HTTP $код $ответ".trim())
        }
        соединение.inputStream?.use { it.readBytes() }
    } finally {
        соединение.disconnect()
    }
}

/**
 * Оповещения приложения: локальные уведомления Android, Telegram и вебхук.
 * Отправка идёт в фоне, чтобы не задерживать опрос сервера.
 */
class Оповещения(
    private val контекст: Context,
    private val настройки: Настройки,
    private val история: () -> История,
    private val журнал: (String) -> Unit,
) {

    private val область = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val очередь = Channel<Задача>(Channel.UNLIMITED)
    private val повторения = mutableMapOf<String, Long>()
    private var счётчикУведомлений = 0

    private data class Задача(val уровень: String, val цель: String, val текст: String)

    init {
        область.launch {
            for (задача in очередь) {
                try {
                    доставить(задача)
                } catch (ошибка: Exception) {
                    журнал(л("Не удалось отправить оповещение: {1}", "${ошибка.message}"))
                }
            }
        }
    }

    private val телеграм: JSONObject get() = настройки.уведомления().optJSONObject("телеграм") ?: JSONObject()
    private val вебхук: JSONObject get() = настройки.уведомления().optJSONObject("вебхук") ?: JSONObject()

    /** Оповещения на телефон включены в настройках. */
    val локальноВключены: Boolean
        get() = настройки.да(настройки.уведомления(), "локальные", true)

    val внешкиВключены: Boolean
        get() = настройки.да(телеграм, "включен", false) || настройки.да(вебхук, "включен", false)

    /**
     * Записывает событие в журнал и рассылает оповещения.
     * Повторяющиеся события подавляются на `повтор_оповещения_минут` минут.
     */
    fun событие(
        уровень: String,
        цель: String,
        текст: String,
        время: Long = System.currentTimeMillis() / 1000,
        оповещать: Boolean = true,
    ) {
        val ключ = "$уровень\u0000$цель\u0000$текст"
        val пауза = настройки.число(настройки.уведомления(), "повтор_оповещения_минут", 30.0) * 60
        val прежнее = повторения[ключ]
        if (прежнее != null && время - прежнее < пауза) return
        повторения[ключ] = время
        if (повторения.size > 500) повторения.clear()

        try {
            история().добавитьСобытие(уровень, цель, текст)
        } catch (ошибка: Exception) {
            журнал(л("Не удалось записать событие: {1}", "${ошибка.message}"))
        }
        журнал("$цель: $текст")
        if (!оповещать) return
        if (локальноВключены) показатьУведомление(уровень, цель, текст)
        if (внешкиВключены) очередь.trySend(Задача(уровень, цель, текст))
    }

    /** Записывает событие только в журнал — без локальных и внешних оповещений. */
    fun записатьБезОповещения(уровень: String, цель: String, текст: String) {
        try {
            история().добавитьСобытие(уровень, цель, текст)
        } catch (ошибка: Exception) {
            журнал(л("Не удалось записать событие: {1}", "${ошибка.message}"))
        }
        журнал("$цель: $текст")
    }

    private fun доставить(задача: Задача) {
        val telegramНастройки = телеграм
        if (настройки.да(telegramНастройки, "включен", false)) {
            val токен = telegramНастройки.optString("токен")
            if (токен.isNotEmpty()) {
                отправитьТелеграм(
                    токен,
                    telegramНастройки.optString("чат_id"),
                    текстОповещения(задача.уровень, задача.цель, задача.текст),
                )
            }
        }
        val вебхукНастройки = вебхук
        if (настройки.да(вебхукНастройки, "включен", false)) {
            val адрес = вебхукНастройки.optString("url")
            if (адрес.isNotEmpty()) {
                val данные = JSONObject()
                    .put("уровень", задача.уровень)
                    .put("цель", задача.цель)
                    .put("сообщение", задача.текст)
                отправитьВебхук(
                    адрес,
                    данные,
                    текстОповещения(задача.уровень, задача.цель, задача.текст, markdown = true),
                )
            }
        }
    }

    private fun показатьУведомление(уровень: String, цель: String, текст: String) {
        val менеджер = NotificationManagerCompat.from(контекст)
        if (!менеджер.areNotificationsEnabled()) return
        val значок = ЗНАЧКИ_УРОВНЕЙ[уровень] ?: "ℹ️"
        val намерение = PendingIntent.getActivity(
            контекст,
            0,
            Intent(контекст, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val канал = if (уровень == "сбой" || уровень == "предупреждение") КАНАЛ_ТРЕВОГ else КАНАЛ_СОБЫТИЙ
        val заголовок = л("{1} — {2}", уровеньСобытия(уровень), Локаль.перевестиГотовый(цель))
        val текстПеревод = Локаль.перевестиГотовый(текст)
        val уведомление = NotificationCompat.Builder(контекст, канал)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("$значок $заголовок")
            .setContentText(текстПеревод)
            .setStyle(NotificationCompat.BigTextStyle().bigText(текстПеревод))
            .setContentIntent(намерение)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            счётчикУведомлений = (счётчикУведомлений + 1) % 100
            менеджер.notify(ОСНОВА_УВЕДОМЛЕНИЙ + счётчикУведомлений, уведомление)
        } catch (_: SecurityException) {
            // разрешение не выдано — просто не показываем уведомление
        }
    }

    /** Тестовая отправка в Telegram: возвращает текст ошибки или null при успехе. */
    fun проверитьТелеграм(токен: String, чат: String): String? = try {
        отправитьТелеграм(
            токен,
            чат,
            текстОповещения("сведения", л("Проверка связи"), л("Тестовое оповещение")),
        )
        null
    } catch (ошибка: Exception) {
        ошибка.message ?: л("неизвестная ошибка")
    }

    /** Тестовая отправка на вебхук: возвращает текст ошибки или null при успехе. */
    fun проверитьВебхук(адрес: String, уровень: String = "сведения"): String? = try {
        val данные = JSONObject()
            .put("уровень", уровень)
            .put("цель", л("Проверка связи"))
            .put("сообщение", л("Тестовое оповещение"))
        отправитьВебхук(
            адрес,
            данные,
            текстОповещения(уровень, л("Проверка связи"), л("Тестовое оповещение"), markdown = true),
        )
        null
    } catch (ошибка: Exception) {
        ошибка.message ?: л("неизвестная ошибка")
    }

    fun закрыть() {
        try {
            область.cancel()
        } catch (_: Exception) {
            // при закрытии приложения ошибки не важны
        }
    }

    companion object {
        const val КАНАЛ_ТРЕВОГ = "тревоги"
        const val КАНАЛ_СОБЫТИЙ = "события"
        const val КАНАЛ_СЛУЖБЫ = "мониторинг"
        const val ОСНОВА_УВЕДОМЛЕНИЙ = 1000
        const val УВЕДОМЛЕНИЕ_СЛУЖБЫ = 1

        /** Создаёт каналы уведомлений — вызывается один раз при запуске приложения. */
        fun создатьКаналы(контекст: Context) {
            val менеджер = контекст.getSystemService(NotificationManager::class.java) ?: return
            менеджер.createNotificationChannel(
                NotificationChannel(
                    КАНАЛ_ТРЕВОГ,
                    контекст.getString(R.string.channel_alerts_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = контекст.getString(R.string.channel_alerts_desc)
                },
            )
            менеджер.createNotificationChannel(
                NotificationChannel(
                    КАНАЛ_СОБЫТИЙ,
                    контекст.getString(R.string.channel_events_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = контекст.getString(R.string.channel_events_desc)
                },
            )
            менеджер.createNotificationChannel(
                NotificationChannel(
                    КАНАЛ_СЛУЖБЫ,
                    контекст.getString(R.string.channel_monitor_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = контекст.getString(R.string.channel_monitor_desc)
                },
            )
        }
    }
}
