package ru.serverastus.monitor.виджет

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Системная точка входа для виджета — связывает его с `ВиджетСервера`. */
class ПриёмникВиджета : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ВиджетСервера()
}
