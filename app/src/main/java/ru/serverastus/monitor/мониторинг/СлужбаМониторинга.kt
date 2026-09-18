package ru.serverastus.monitor.мониторинг

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import ru.serverastus.monitor.MainActivity
import ru.serverastus.monitor.Монитор
import ru.serverastus.monitor.R
import ru.serverastus.monitor.данные.Снимок
import ru.serverastus.monitor.данные.форматПроцент
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.виджет.ВиджетСервера

/**
 * Фоновая служба мониторинга: держит постоянное уведомление со сводкой и
 * не даёт системе остановить опрос сервера, когда окно приложения закрыто.
 */
class СлужбаМониторинга : Service() {

    private val область = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var наблюдение: Job? = null
    private var движок: Движок? = null
    private var прошлыйТекст = ""
    private var прошлыйКлючВиджета = ""

    override fun onBind(намерение: Intent?): IBinder? = null

    override fun onStartCommand(намерение: Intent?, флаги: Int, идЗапуска: Int): Int {
        val приложение = application as? Монитор ?: return START_NOT_STICKY
        val опрос = приложение.движок
        движок = опрос

        when (намерение?.action) {
            ДЕЙСТВИЕ_СТОП -> {
                опрос.остановить()
                остановитьСлужбу()
                return START_NOT_STICKY
            }

            ДЕЙСТВИЕ_ПАУЗА -> if (опрос.наПаузе) опрос.продолжить() else опрос.пауза()

            ДЕЙСТВИЕ_ОБНОВИТЬ -> опрос.проверитьСейчас()

            else -> опрос.запустить()
        }

        показатьУведомление(опрос.снимок.value)
        if (наблюдение == null) {
            наблюдение = область.launch {
                опрос.снимок.collectLatest { снимок ->
                    показатьУведомление(снимок)
                    возможноОбновитьВиджет(снимок)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        наблюдение?.cancel()
        наблюдение = null
        движок?.остановить()
        движок = null
        область.cancel()
        super.onDestroy()
    }

    // --- уведомление -----------------------------------------------------

    private fun показатьУведомление(снимок: Снимок) {
        val текст = сводка(снимок)
        if (текст == прошлыйТекст) return
        прошлыйТекст = текст

        val уведомление = собратьУведомление(снимок, текст)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(
                    this,
                    Оповещения.УВЕДОМЛЕНИЕ_СЛУЖБЫ,
                    уведомление,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(Оповещения.УВЕДОМЛЕНИЕ_СЛУЖБЫ, уведомление)
            }
        } catch (_: Exception) {
            // уведомления запрещены пользователем или служба уже остановлена
        }
    }

    /**
     * Обновляет виджет на рабочем столе, только если поменялось что-то, что на
     * нём реально видно — иначе виджет перерисовывался бы на каждый чих движка
     * (например, при получении очередной строки техжурнала).
     */
    private fun возможноОбновитьВиджет(снимок: Снимок) {
        val проверки = снимок.проверки.values
        val ключ = listOf(
            снимок.серверId,
            снимок.имяПрофиля,
            снимок.имяСервера,
            снимок.связь,
            снимок.метрики?.цп,
            снимок.метрики?.озуПроцент,
            снимок.метрики?.дискПроцент,
            снимок.оценкаСети?.балл,
            снимок.оценкаСети?.уровень,
            снимок.оценкаОбслуживания?.балл,
            снимок.оценкаОбслуживания?.уровень,
            проверки.count { it.доступно },
            проверки.size,
            снимок.пауза,
        ).joinToString("|")
        if (ключ == прошлыйКлючВиджета) return
        прошлыйКлючВиджета = ключ
        область.launch { ВиджетСервера.обновитьВсе(this@СлужбаМониторинга) }
    }

    private fun сводка(снимок: Снимок): String {
        val части = mutableListOf<String>()
        части.add(снимок.текстСвязи)
        снимок.действиеИдёт?.let { части.add(л("выполняется: {1}", it)) }
        снимок.метрики?.let { метрики ->
            метрики.цп?.let { части.add(л("ЦП {1}", форматПроцент(it))) }
            метрики.озуПроцент?.let { части.add(л("ОЗУ {1}", форматПроцент(it))) }
            метрики.дискПроцент?.let { части.add(л("диск {1}", форматПроцент(it))) }
        }
        if (снимок.пауза) части.add(л("пауза"))
        return части.joinToString(" · ")
    }

    private fun собратьУведомление(снимок: Снимок, текст: String): Notification {
        val открыть = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val заголовок = снимок.имяПрофиля.ifBlank { снимок.имяСервера }.ifBlank { getString(R.string.app_name) }

        val построитель = NotificationCompat.Builder(this, Оповещения.КАНАЛ_СЛУЖБЫ)
            .setContentTitle(заголовок)
            .setContentText(текст)
            .setStyle(NotificationCompat.BigTextStyle().bigText(текст))
            .setSmallIcon(R.drawable.ic_notify)
            .setContentIntent(открыть)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, л("Обновить"), службаНамерение(ДЕЙСТВИЕ_ОБНОВИТЬ, 11))
            .addAction(
                0,
                if (снимок.пауза) л("Продолжить") else л("Пауза"),
                службаНамерение(ДЕЙСТВИЕ_ПАУЗА, 12),
            )
            .addAction(0, л("Остановить"), службаНамерение(ДЕЙСТВИЕ_СТОП, 13))

        return построитель.build()
    }

    private fun службаНамерение(действие: String, код: Int): PendingIntent {
        val намерение = Intent(this, СлужбаМониторинга::class.java).setAction(действие)
        return PendingIntent.getService(
            this,
            код,
            намерение,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun остановитьСлужбу() {
        try {
            NotificationManagerCompat.from(this).cancel(Оповещения.УВЕДОМЛЕНИЕ_СЛУЖБЫ)
        } catch (_: Exception) {
            // отменить уведомление не удалось — не мешает остановке службы
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ДЕЙСТВИЕ_СТАРТ = "ru.serverastus.monitor.ДЕЙСТВИЕ_СТАРТ"
        const val ДЕЙСТВИЕ_СТОП = "ru.serverastus.monitor.ДЕЙСТВИЕ_СТОП"
        const val ДЕЙСТВИЕ_ПАУЗА = "ru.serverastus.monitor.ДЕЙСТВИЕ_ПАУЗА"
        const val ДЕЙСТВИЕ_ОБНОВИТЬ = "ru.serverastus.monitor.ДЕЙСТВИЕ_ОБНОВИТЬ"

        fun запустить(контекст: Context) = отправить(контекст, ДЕЙСТВИЕ_СТАРТ)

        fun остановить(контекст: Context) = отправить(контекст, ДЕЙСТВИЕ_СТОП)

        fun паузаИлиПродолжить(контекст: Context) = отправить(контекст, ДЕЙСТВИЕ_ПАУЗА)

        fun обновить(контекст: Context) = отправить(контекст, ДЕЙСТВИЕ_ОБНОВИТЬ)

        private fun отправить(контекст: Context, действие: String) {
            val намерение = Intent(контекст, СлужбаМониторинга::class.java).setAction(действие)
            try {
                if (действие == ДЕЙСТВИЕ_СТОП) {
                    контекст.startService(намерение)
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    контекст.startForegroundService(намерение)
                } else {
                    контекст.startService(намерение)
                }
            } catch (_: Exception) {
                // система запретила запуск службы из фона — мониторинг останется выключен
            }
        }
    }
}
