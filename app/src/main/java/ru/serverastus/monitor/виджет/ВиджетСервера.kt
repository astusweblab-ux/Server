package ru.serverastus.monitor.виджет

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.serverastus.monitor.MainActivity
import ru.serverastus.monitor.Монитор
import ru.serverastus.monitor.данные.Метрики
import ru.serverastus.monitor.данные.ОценкаОбслуживания
import ru.serverastus.monitor.данные.ОценкаСети
import ru.serverastus.monitor.данные.УРОВЕНЬ_ОБСЛУЖИВАНИЯ_ОТЛИЧНО
import ru.serverastus.monitor.данные.УРОВЕНЬ_ОБСЛУЖИВАНИЯ_НОРМАЛЬНО
import ru.serverastus.monitor.данные.УРОВЕНЬ_ОБСЛУЖИВАНИЯ_ПЛОХО
import ru.serverastus.monitor.данные.УРОВЕНЬ_СЕТИ_ОТЛИЧНО
import ru.serverastus.monitor.данные.УРОВЕНЬ_СЕТИ_НОРМАЛЬНО
import ru.serverastus.monitor.данные.УРОВЕНЬ_СЕТИ_ПЛОХО
import ru.serverastus.monitor.данные.времяТекст
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.форматПроцент
import ru.serverastus.monitor.мониторинг.СлужбаМониторинга
import ru.serverastus.monitor.мониторинг.параметрыСервера

/** Свои цвета для виджета: Glance рисует их через RemoteViews, а не через тему приложения. */
private object ПалитраВиджета {
    val ФОН = Color(0xFF131C30)
    val ТЕКСТ = Color(0xFFE9EEFB)
    val ТУСКЛЫЙ = Color(0xFF8C9BC0)
    val СИНИЙ = Color(0xFF4FB0FF)
    val ЗЕЛЁНЫЙ = Color(0xFF31D17B)
    val ЖЁЛТЫЙ = Color(0xFFF5C451)
    val КРАСНЫЙ = Color(0xFFF0574F)

    fun нагрузки(процент: Double?): Color = when {
        процент == null -> ТУСКЛЫЙ
        процент >= 90.0 -> КРАСНЫЙ
        процент >= 72.0 -> ЖЁЛТЫЙ
        else -> ЗЕЛЁНЫЙ
    }

    /** Тот же цвет по уровню, что у «Сеть»/«Обслуживание» в самом приложении. */
    fun уровняОценки(уровень: String?): Color = when (уровень) {
        УРОВЕНЬ_СЕТИ_ОТЛИЧНО, УРОВЕНЬ_ОБСЛУЖИВАНИЯ_ОТЛИЧНО -> ЗЕЛЁНЫЙ
        УРОВЕНЬ_СЕТИ_НОРМАЛЬНО, УРОВЕНЬ_ОБСЛУЖИВАНИЯ_НОРМАЛЬНО -> СИНИЙ
        УРОВЕНЬ_СЕТИ_ПЛОХО, УРОВЕНЬ_ОБСЛУЖИВАНИЯ_ПЛОХО -> КРАСНЫЙ
        null -> ТУСКЛЫЙ
        else -> ЖЁЛТЫЙ // «внимание»
    }
}

/** Пороги высоты виджета, при которых появляются дополнительные строки. */
private object Пороги {
    val ОЦЕНКИ: Dp = 135.dp
    val ПРОВЕРКИ: Dp = 175.dp
}

/** Снимок данных для виджета — либо из живого движка, либо из истории на диске. */
private data class ДанныеВиджета(
    val сервер: String,
    val связь: Boolean?,
    val метрики: Метрики?,
    val оценкаСети: ОценкаСети?,
    val оценкаОбслуживания: ОценкаОбслуживания?,
    val доступно: Int,
    val всего: Int,
    val время: Long,
    val пауза: Boolean,
    val естьДанные: Boolean,
)

private suspend fun собратьДанные(context: Context): ДанныеВиджета = withContext(Dispatchers.IO) {
    val приложение = context.applicationContext as Монитор
    val снимок = приложение.движок.снимок.value
    if (снимок.метрики != null || снимок.проверки.isNotEmpty()) {
        val проверки = снимок.проверки.values
        ДанныеВиджета(
            сервер = снимок.имяПрофиля.ifBlank { снимок.имяСервера.ifBlank { параметрыСервера(приложение.настройки).хост } },
            связь = снимок.связь,
            метрики = снимок.метрики,
            оценкаСети = снимок.оценкаСети,
            оценкаОбслуживания = снимок.оценкаОбслуживания,
            доступно = проверки.count { it.доступно },
            всего = проверки.size,
            время = снимок.последнийОпрос,
            пауза = снимок.пауза,
            естьДанные = true,
        )
    } else {
        // Живых данных ещё нет (свежая установка виджета или служба не запускалась в
        // этом процессе) — берём последнее, что реально измерялось на сервере. Оценки
        // «Сеть»/«Обслуживание» в историю не пишутся (только метрики и проверки), так
        // что на холодном старте они будут пустыми, пока не откроется приложение.
        val история = приложение.история
        val метрики = история.последниеМетрики()
        val проверки = история.последниеПроверки()
        val время = maxOf(метрики?.время ?: 0L, проверки.maxOfOrNull { it.время } ?: 0L)
        ДанныеВиджета(
            сервер = приложение.настройки.подпись().ifBlank { параметрыСервера(приложение.настройки).хост },
            связь = null,
            метрики = метрики,
            оценкаСети = null,
            оценкаОбслуживания = null,
            доступно = проверки.count { it.доступно },
            всего = проверки.size,
            время = время,
            пауза = false,
            естьДанные = метрики != null || проверки.isNotEmpty(),
        )
    }
}

/** Действие кнопки «⟳» на виджете: просит службу опросить сервер внеочередно. */
class ДействиеОбновитьВиджет : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        СлужбаМониторинга.обновить(context)
    }
}

/**
 * Виджет на рабочий стол: сводка по серверу (ЦП/ОЗУ/диск, оценки «Сеть» и
 * «Обслуживание», доступность сайтов и портов) без открытия приложения. Данные
 * берутся из уже собранного снимка движка, а если его ещё нет — из истории на
 * диске, поэтому виджет не делает собственных сетевых запросов. Размер —
 * [SizeMode.Exact]: виджет по-настоящему масштабируется (пользователь может
 * растянуть или сжать его на рабочем столе), а не показывает одну и ту же
 * вёрстку — лишние строки появляются только когда для них хватает высоты.
 */
class ВиджетСервера : GlanceAppWidget() {

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val данные = собратьДанные(context)
        provideContent {
            СодержимоеВиджета(данные)
        }
    }

    companion object {
        /** Вызывается движком при каждом новом снимке, чтобы виджет не отставал от приложения. */
        suspend fun обновитьВсе(context: Context) {
            ВиджетСервера().updateAll(context)
        }
    }
}

private fun цветСвязи(данные: ДанныеВиджета): Color = when (данные.связь) {
    true -> ПалитраВиджета.ЗЕЛЁНЫЙ
    false -> ПалитраВиджета.КРАСНЫЙ
    null -> ПалитраВиджета.ТУСКЛЫЙ
}

@Composable
private fun СодержимоеВиджета(данные: ДанныеВиджета) {
    val context = LocalContext.current
    val размер = LocalSize.current
    val показатьОценки = размер.height >= Пороги.ОЦЕНКИ
    val показатьПроверки = размер.height >= Пороги.ПРОВЕРКИ

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(ПалитраВиджета.ФОН))
            .cornerRadius(16.dp)
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Box(
                modifier = GlanceModifier
                    .size(8.dp)
                    .cornerRadius(4.dp)
                    .background(ColorProvider(цветСвязи(данные))),
            ) {}
            Spacer(modifier = GlanceModifier.width(6.dp))
            Text(
                text = данные.сервер.ifBlank { л("Сервер") },
                style = TextStyle(
                    color = ColorProvider(ПалитраВиджета.ТЕКСТ),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                ),
                maxLines = 1,
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = "⟳",
                style = TextStyle(color = ColorProvider(ПалитраВиджета.ТУСКЛЫЙ), fontSize = 16.sp),
                modifier = GlanceModifier.clickable(actionRunCallback<ДействиеОбновитьВиджет>()),
            )
        }

        Spacer(modifier = GlanceModifier.height(8.dp))

        if (!данные.естьДанные) {
            Text(
                text = л("Нет данных — откройте приложение"),
                style = TextStyle(color = ColorProvider(ПалитраВиджета.ТУСКЛЫЙ), fontSize = 12.sp),
            )
        } else {
            val метрики = данные.метрики
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                СтрокаПоказателя(л("ЦП"), метрики?.цп, GlanceModifier.defaultWeight())
                СтрокаПоказателя(л("ОЗУ"), метрики?.озуПроцент, GlanceModifier.defaultWeight())
                СтрокаПоказателя(л("Диск"), метрики?.дискПроцент, GlanceModifier.defaultWeight())
            }

            if (показатьОценки) {
                Spacer(modifier = GlanceModifier.height(8.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    СтрокаОценки(л("Сеть"), данные.оценкаСети?.балл, данные.оценкаСети?.уровень, GlanceModifier.defaultWeight())
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    СтрокаОценки(
                        л("Обслуживание"),
                        данные.оценкаОбслуживания?.балл,
                        данные.оценкаОбслуживания?.уровень,
                        GlanceModifier.defaultWeight(),
                    )
                }
            }

            if (показатьПроверки) {
                Spacer(modifier = GlanceModifier.height(8.dp))
                val цветПроверок = when {
                    данные.всего == 0 -> ПалитраВиджета.ТУСКЛЫЙ
                    данные.доступно == данные.всего -> ПалитраВиджета.ЗЕЛЁНЫЙ
                    данные.доступно == 0 -> ПалитраВиджета.КРАСНЫЙ
                    else -> ПалитраВиджета.ЖЁЛТЫЙ
                }
                Text(
                    text = if (данные.всего > 0) {
                        л("Сайты и порты: {1} из {2}", данные.доступно, данные.всего)
                    } else {
                        л("Сайты и порты не настроены")
                    },
                    style = TextStyle(color = ColorProvider(цветПроверок), fontSize = 12.sp),
                )

                Spacer(modifier = GlanceModifier.height(4.dp))

                val подпись = buildString {
                    if (данные.время > 0) append(л("Обновлено {1}", времяТекст(данные.время)))
                    if (данные.пауза) append(if (isEmpty()) л("На паузе") else л(" · на паузе"))
                }
                if (подпись.isNotEmpty()) {
                    Text(
                        text = подпись,
                        style = TextStyle(color = ColorProvider(ПалитраВиджета.ТУСКЛЫЙ), fontSize = 10.sp),
                    )
                }
            }
        }
    }
}

@Composable
private fun СтрокаПоказателя(метка: String, процент: Double?, modifier: GlanceModifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Horizontal.CenterHorizontally) {
        Text(
            text = форматПроцент(процент),
            style = TextStyle(
                color = ColorProvider(ПалитраВиджета.нагрузки(процент)),
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
            ),
        )
        Text(
            text = метка,
            style = TextStyle(color = ColorProvider(ПалитраВиджета.ТУСКЛЫЙ), fontSize = 10.sp),
        )
    }
}

@Composable
private fun СтрокаОценки(метка: String, балл: Int?, уровень: String?, modifier: GlanceModifier) {
    Column(
        modifier = modifier
            .background(ColorProvider(Color(0x1AFFFFFF)))
            .cornerRadius(8.dp)
            .padding(6.dp),
    ) {
        Text(
            text = метка,
            style = TextStyle(color = ColorProvider(ПалитраВиджета.ТУСКЛЫЙ), fontSize = 10.sp),
            maxLines = 1,
        )
        Text(
            text = if (балл != null) "$балл — ${л(уровень ?: "")}" else "—",
            style = TextStyle(
                color = ColorProvider(ПалитраВиджета.уровняОценки(уровень)),
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
            ),
            maxLines = 1,
        )
    }
}
