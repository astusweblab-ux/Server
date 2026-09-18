package ru.serverastus.monitor.экран

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import ru.serverastus.monitor.данные.л

/**
 * Русский вариант `Text`: в проекте принято называть параметры по-русски,
 * поэтому обёртка принимает «текст», а в остальном повторяет material3.
 */
@Composable
fun Текст(
    текст: String,
    modifier: Modifier = Modifier,
    color: Color = Палитра.ТЕКСТ,
    style: TextStyle? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
    overflow: TextOverflow = TextOverflow.Clip,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = текст,
        modifier = modifier,
        color = color,
        style = style ?: MaterialTheme.typography.bodyMedium,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        textAlign = textAlign,
        overflow = overflow,
        maxLines = maxLines,
    )
}

/**
 * Прокручиваемый контейнер вкладки: содержимое экранов не помещается на телефоне,
 * поэтому каждая вкладка (кроме настроек со своим списком) оборачивается сюда.
 */
@Composable
fun Прокрутка(содержимое: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = содержимое,
    )
}

/** Карточка с заголовком — основной контейнер всех экранов. */
@Composable
fun Карточка(
    заголовок: String? = null,
    подзаголовок: String? = null,
    справа: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    содержимое: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Палитра.КАРТОЧКА)
            .border(1.dp, Палитра.ГРАНИЦА, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        if (заголовок != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Текст(
                    текст = заголовок.uppercase(),
                    color = Палитра.ТУСКЛЫЙ,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                )
                справа?.invoke()
            }
            Spacer(Modifier.height(10.dp))
            if (подзаголовок != null) {
                Текст(
                    текст = подзаголовок,
                    color = Палитра.ТУСКЛЫЙ,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
        содержимое()
    }
}

/** Строка «подпись — значение». */
@Composable
fun СтрокаЗначения(
    подпись: String,
    значение: String,
    цвет: Color = Палитра.ТЕКСТ,
    моно: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Текст(
            текст = подпись,
            color = Палитра.ТУСКЛЫЙ,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        Текст(
            текст = значение,
            color = цвет,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = if (моно) FontFamily.Monospace else FontFamily.Default,
                fontWeight = FontWeight.Medium,
            ),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.3f),
        )
    }
}

/** Круглый прибор со стрелкой-дугой (аналог Gauge из настольной версии). */
@Composable
fun Кольцо(
    значение: Double?,
    подпись: String,
    текст: String,
    цвет: Color,
    размер: Dp = 118.dp,
    порог: Double = 90.0,
) {
    val доля = ((значение ?: 0.0) / 100.0).coerceIn(0.0, 1.0).toFloat()
    val плавно by animateFloatAsState(targetValue = доля, animationSpec = tween(600), label = подпись)

    Box(modifier = Modifier.size(размер), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val толщина = size.minDimension * 0.11f
            val отступ = толщина / 2f
            val рамка = size.minDimension - толщина
            drawArc(
                color = Палитра.КАРТОЧКА_ВЫШЕ,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(отступ, отступ),
                size = androidx.compose.ui.geometry.Size(рамка, рамка),
                style = Stroke(width = толщина, cap = StrokeCap.Round),
            )
            if (плавно > 0.001f) {
                drawArc(
                    color = цвет,
                    startAngle = 135f,
                    sweepAngle = 270f * плавно,
                    useCenter = false,
                    topLeft = androidx.compose.ui.geometry.Offset(отступ, отступ),
                    size = androidx.compose.ui.geometry.Size(рамка, рамка),
                    style = Stroke(width = толщина, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Текст(
                текст = текст,
                color = цвет,
                fontSize = (размер.value * 0.19f).sp,
                fontWeight = FontWeight.Bold,
            )
            Текст(
                текст = подпись,
                color = Палитра.ТУСКЛЫЙ,
                fontSize = (размер.value * 0.10f).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (значение != null) {
                Текст(
                    текст = л("порог {1}%", порог.toInt()),
                    color = Палитра.ТУСКЛЫЙ.copy(alpha = 0.7f),
                    fontSize = (размер.value * 0.085f).sp,
                )
            }
        }
    }
}

/** Одна линия графика. */
data class ЛинияГрафика(val значения: List<Float>, val цвет: Color, val заливка: Boolean = false)

/** Многолинейный график со сглаживанием — аналог LineChart. */
@Composable
fun График(
    линии: List<ЛинияГрафика>,
    modifier: Modifier = Modifier,
    высота: Dp = 120.dp,
    минимум: Float? = null,
    максимум: Float? = null,
) {
    val все = линии.flatMap { it.значения }
    val низ = минимум ?: (все.minOrNull() ?: 0f)
    val верх = максимум ?: (все.maxOrNull() ?: 1f)
    val диапазон = max(верх - низ, 0.0001f)

    Canvas(modifier = modifier.fillMaxWidth().height(высота)) {
        val толщинаСетки = 1f
        val цветСетки = Палитра.ГРАНИЦА.copy(alpha = 0.6f)
        for (доля in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val y = size.height * доля
            drawLine(цветСетки, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), толщинаСетки)
        }
        линии.forEach { линия ->
            if (линия.значения.size < 2) return@forEach
            val точкаX: (Int) -> Float = { индекс ->
                size.width * индекс.toFloat() / (линия.значения.size - 1).toFloat()
            }
            val точкаY: (Float) -> Float = { значение ->
                size.height - size.height * ((значение - низ) / диапазон).coerceIn(0f, 1f)
            }
            val путь = Path()
            линия.значения.forEachIndexed { индекс, значение ->
                val x = точкаX(индекс)
                val y = точкаY(значение)
                if (индекс == 0) путь.moveTo(x, y) else путь.lineTo(x, y)
            }
            if (линия.заливка) {
                val заливкаПуть = Path().apply {
                    addPath(путь)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(заливкаПуть, линия.цвет.copy(alpha = 0.16f))
            }
            drawPath(путь, линия.цвет, style = Stroke(width = 2.5f * density, cap = StrokeCap.Round))
        }
    }
}

/** Маленький график без сетки — для карточек целей. */
@Composable
fun Спарклайн(значения: List<Float>, цвет: Color, modifier: Modifier = Modifier, высота: Dp = 34.dp) {
    if (значения.isEmpty()) return
    val низ = значения.minOrNull() ?: 0f
    val верх = значения.maxOrNull() ?: 1f
    val диапазон = max(верх - низ, 0.0001f)
    Canvas(modifier = modifier.fillMaxWidth().height(высота)) {
        val шаг = if (значения.size > 1) size.width / (значения.size - 1).toFloat() else size.width
        val путь = Path()
        значения.forEachIndexed { индекс, значение ->
            val x = шаг * индекс
            val y = size.height - 2f - (size.height - 4f) * ((значение - низ) / диапазон).coerceIn(0f, 1f)
            if (индекс == 0) путь.moveTo(x, y) else путь.lineTo(x, y)
        }
        val заливка = Path().apply {
            addPath(путь)
            lineTo(шаг * (значения.size - 1), size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(заливка, цвет.copy(alpha = 0.18f))
        drawPath(путь, цвет, style = Stroke(width = 2f * density, cap = StrokeCap.Round))
    }
}

/** Элемент списка-гистограммы. */
data class Полоска(val подпись: String, val значение: String, val доля: Float, val цвет: Color)

/** Список горизонтальных полосок — аналог BarList (топ процессов, тома). */
@Composable
fun Полоски(элементы: List<Полоска>, подписьПусто: String = л("Нет данных")) {
    if (элементы.isEmpty()) {
        Текст(подписьПусто, color = Палитра.ТУСКЛЫЙ, style = MaterialTheme.typography.bodySmall)
        return
    }
    Column {
        элементы.forEach { элемент ->
            val доля by animateFloatAsState(
                targetValue = элемент.доля.coerceIn(0f, 1f),
                animationSpec = tween(500),
                label = элемент.подпись,
            )
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Текст(
                        текст = элемент.подпись,
                        color = Палитра.ТЕКСТ,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Текст(
                        текст = элемент.значение,
                        color = Палитра.ТУСКЛЫЙ,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Canvas(modifier = Modifier.fillMaxWidth().height(7.dp)) {
                    val радиус = size.height / 2f
                    drawRoundRect(
                        color = Палитра.КАРТОЧКА_ВЫШЕ,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(радиус, радиус),
                    )
                    val ширина = size.width * доля
                    if (ширина > 0.5f) {
                        drawRoundRect(
                            color = элемент.цвет,
                            size = androidx.compose.ui.geometry.Size(max(ширина, радиус * 2f), size.height),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(радиус, радиус),
                        )
                    }
                }
            }
        }
    }
}

/** Пульсирующая лампа состояния: медленно «дышит», когда всё хорошо и чаще — при сбое. */
@Composable
fun Лампа(цвет: Color, размер: Dp = 14.dp, пульсирует: Boolean = true, периодМс: Int = 2200) {
    val переход = rememberInfiniteTransition(label = л("лампа"))
    val дыхание by переход.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(периодМс), RepeatMode.Reverse),
        label = л("дыхание"),
    )
    val прозрачность = if (пульсирует) дыхание else 1f
    Canvas(modifier = Modifier.size(размер)) {
        val радиус = size.minDimension / 2f
        drawCircle(цвет.copy(alpha = 0.22f * прозрачность), radius = радиус)
        drawCircle(цвет.copy(alpha = прозрачность), radius = радиус * 0.62f)
    }
}

/** Вращающаяся дуга — индикатор «идёт опрос». */
@Composable
fun Крутилка(цвет: Color = Палитра.АКЦЕНТ, размер: Dp = 18.dp) {
    val переход = rememberInfiniteTransition(label = л("крутилка"))
    val угол by переход.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900)),
        label = л("вращение"),
    )
    Canvas(modifier = Modifier.size(размер)) {
        val толщина = size.minDimension * 0.18f
        drawArc(
            color = Палитра.КАРТОЧКА_ВЫШЕ,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = толщина),
        )
        drawArc(
            color = цвет,
            startAngle = угол,
            sweepAngle = 90f,
            useCenter = false,
            style = Stroke(width = толщина, cap = StrokeCap.Round),
        )
    }
}

/** Цветной ярлычок состояния («работает», «сбой»). */
@Composable
fun Ярлычок(текст: String, цвет: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(цвет.copy(alpha = 0.16f))
            .border(1.dp, цвет.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Текст(текст, color = цвет, style = MaterialTheme.typography.labelSmall)
    }
}

/** Небольшая плитка «подпись — значение» для сеток показателей. */
@Composable
fun Плитка(подпись: String, значение: String, цвет: Color = Палитра.ТЕКСТ, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Палитра.ПАНЕЛЬ)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Текст(подпись, color = Палитра.ТУСКЛЫЙ, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Текст(
            текст = значение,
            color = цвет,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Пустая заглушка «данных пока нет». */
@Composable
fun Пусто(текст: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Текст(текст, color = Палитра.ТУСКЛЫЙ, style = MaterialTheme.typography.bodySmall)
    }
}
