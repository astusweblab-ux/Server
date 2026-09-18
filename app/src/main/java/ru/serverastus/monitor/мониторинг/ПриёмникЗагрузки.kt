package ru.serverastus.monitor.мониторинг

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ru.serverastus.monitor.Монитор
import ru.serverastus.monitor.данные.л

/** Запускает мониторинг после перезагрузки телефона, если это разрешено настройками. */
class ПриёмникЗагрузки : BroadcastReceiver() {

    override fun onReceive(контекст: Context, намерение: Intent) {
        if (намерение.action != Intent.ACTION_BOOT_COMPLETED) return
        val приложение = контекст.applicationContext as? Монитор ?: return
        if (!приложение.настройки.да(приложение.настройки.данные, "запускать_при_загрузке", true)) return
        приложение.журнал(л("Телефон перезагружен — запускаю мониторинг"))
        СлужбаМониторинга.запустить(контекст)
    }
}
