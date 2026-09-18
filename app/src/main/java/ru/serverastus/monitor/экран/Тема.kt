package ru.serverastus.monitor.экран

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Палитра приложения — та же, что у настольной версии (theme.py). */
object Палитра {
    val ФОН = Color(0xFF0B1120)
    val ПАНЕЛЬ = Color(0xFF131C30)
    val КАРТОЧКА = Color(0xFF182239)
    val КАРТОЧКА_ВЫШЕ = Color(0xFF1F2C4A)
    val ГРАНИЦА = Color(0xFF27334F)
    val ТЕКСТ = Color(0xFFE9EEFB)
    val ТУСКЛЫЙ = Color(0xFF8C9BC0)
    val АКЦЕНТ = Color(0xFF3EA6FF)
    val СИНИЙ = Color(0xFF4FB0FF)
    val ЗЕЛЁНЫЙ = Color(0xFF31D17B)
    val ЖЁЛТЫЙ = Color(0xFFF5C451)
    val КРАСНЫЙ = Color(0xFFF0574F)
    val ФИОЛЕТОВЫЙ = Color(0xFFA97BFF)
    val ОРАНЖЕВЫЙ = Color(0xFFFF9F45)

    /** Цвет уровня события журнала — как в настольном приложении. */
    fun уровня(уровень: String): Color = when (уровень) {
        "сбой" -> КРАСНЫЙ
        "предупреждение" -> ЖЁЛТЫЙ
        "успех" -> ЗЕЛЁНЫЙ
        else -> ТУСКЛЫЙ
    }

    /** Цвет по проценту загрузки: спокойный, тревожный, критический. */
    fun нагрузки(процент: Double?, порог: Double = 90.0): Color = when {
        процент == null -> ТУСКЛЫЙ
        процент >= порог -> КРАСНЫЙ
        процент >= порог * 0.8 -> ЖЁЛТЫЙ
        else -> ЗЕЛЁНЫЙ
    }
}

private val СХЕМА = darkColorScheme(
    primary = Палитра.АКЦЕНТ,
    onPrimary = Палитра.ФОН,
    secondary = Палитра.СИНИЙ,
    onSecondary = Палитра.ФОН,
    tertiary = Палитра.ФИОЛЕТОВЫЙ,
    background = Палитра.ФОН,
    onBackground = Палитра.ТЕКСТ,
    surface = Палитра.КАРТОЧКА,
    onSurface = Палитра.ТЕКСТ,
    surfaceVariant = Палитра.ПАНЕЛЬ,
    onSurfaceVariant = Палитра.ТУСКЛЫЙ,
    outline = Палитра.ГРАНИЦА,
    error = Палитра.КРАСНЫЙ,
    onError = Палитра.ФОН,
)

private val ТИПОГРАФИЯ = Typography(
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
    ),
    bodyMedium = TextStyle(fontFamily = FontFamily.Default, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.Default, fontSize = 12.sp),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
    ),
)

/**
 * Тёмная тема приложения. Светлую тему не поддерживаем осознанно:
 * приборы и графики рассчитаны на тёмный фон, как в настольной версии.
 */
@Composable
fun ТемаПриложения(@Suppress("UNUSED_PARAMETER") тёмная: Boolean = isSystemInDarkTheme(), содержимое: @Composable () -> Unit) {
    MaterialTheme(colorScheme = СХЕМА, typography = ТИПОГРАФИЯ, content = содержимое)
}
