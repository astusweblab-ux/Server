package ru.serverastus.monitor.экран

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.serverastus.monitor.данные.РезультатПроверки
import ru.serverastus.monitor.данные.Снимок
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.форматЗадержка
import ru.serverastus.monitor.данные.форматПроцент
import ru.serverastus.monitor.данные.времяДата

/** Вкладка «Сайты и порты»: состояние всех целей, статистика за сутки и история проверок. */
@Composable
fun ЭкранПроверки(снимок: Снимок) {
    val сайты = снимок.проверки.values.filter { it.вид == "http" }.sortedBy { it.имя }
    val порты = снимок.проверки.values.filter { it.вид != "http" }.sortedBy { it.имя }

    Прокрутка {
        Карточка(
            заголовок = л("Итоги"),
            справа = {
                Ярлычок(
                    текст = итог(снимок),
                    цвет = цветИтога(снимок),
                )
            },
        ) {
            val все = снимок.проверки.values
            val доступных = все.count { it.доступно }
            СтрокаЗначения(л("Целей проверено"), все.size.toString())
            СтрокаЗначения(л("Доступно сейчас"), л("{1} из {2}", доступных, все.size), цвет = if (доступных == все.size) Палитра.ЗЕЛЁНЫЙ else Палитра.КРАСНЫЙ)
            СтрокаЗначения(л("Сайтов"), сайты.size.toString())
            СтрокаЗначения(л("Портов"), порты.size.toString())
            if (все.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                Пусто(л("Проверки ещё не выполнялись"))
            }
        }

        if (сайты.isNotEmpty()) {
            Карточка(заголовок = л("Сайты")) {
                сайты.forEachIndexed { индекс, проверка ->
                    if (индекс > 0) Разделитель()
                    СтрокаПроверки(проверка, снимок)
                }
            }
        }

        if (порты.isNotEmpty()) {
            Карточка(заголовок = л("Порты")) {
                порты.forEachIndexed { индекс, проверка ->
                    if (индекс > 0) Разделитель()
                    СтрокаПроверки(проверка, снимок)
                }
            }
        }

        if (снимок.проверки.isEmpty()) {
            Карточка(заголовок = л("Цели")) {
                Пусто(л("Список сайтов и портов пуст — заполните его в настройках"))
            }
        }
    }
}

@Composable
private fun Разделитель() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Палитра.ГРАНИЦА))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun СтрокаПроверки(проверка: РезультатПроверки, снимок: Снимок) {
    val статистика = снимок.статистика[проверка.имя]
    val история = снимок.историяПроверок[проверка.имя].orEmpty()
    val цвет = if (проверка.доступно) Палитра.ЗЕЛЁНЫЙ else Палитра.КРАСНЫЙ

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Лампа(цвет = цвет, пульсирует = true, периодМс = if (проверка.доступно) 2600 else 1200)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Текст(
                    текст = проверка.имя,
                    color = Палитра.ТЕКСТ,
                    style = MaterialTheme.typography.titleMedium,
                )
                Текст(
                    текст = проверка.адрес,
                    color = Палитра.ТУСКЛЫЙ,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Ярлычок(
                текст = if (проверка.доступно) л("работает") else л("недоступно"),
                цвет = цвет,
            )
        }

        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Плитка(л("Ответ"), форматЗадержка(проверка.времяМс), modifier = Modifier.weight(1f))
            Плитка(
                подпись = if (проверка.вид == "http") л("Код") else л("Порт"),
                значение = проверка.код?.toString() ?: "—",
                modifier = Modifier.weight(1f),
            )
            Плитка(
                подпись = "SSL",
                значение = проверка.sslДней?.let { л("{1} дн.", it) } ?: "—",
                цвет = when {
                    проверка.sslДней == null -> Палитра.ТЕКСТ
                    проверка.sslДней <= 7 -> Палитра.КРАСНЫЙ
                    проверка.sslДней <= 21 -> Палитра.ЖЁЛТЫЙ
                    else -> Палитра.ЗЕЛЁНЫЙ
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (проверка.ошибка != null) {
            Spacer(Modifier.height(6.dp))
            Текст(
                текст = проверка.ошибка,
                color = Палитра.КРАСНЫЙ,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (статистика != null && статистика.проверок > 0) {
            Spacer(Modifier.height(6.dp))
            СтрокаЗначения(
                л("Доступность за сутки"),
                л("{1} (проверок: {2})", форматПроцент(статистика.доступность), статистика.проверок),
                цвет = when {
                    статистика.доступность == null -> Палитра.ТЕКСТ
                    статистика.доступность >= 99.0 -> Палитра.ЗЕЛЁНЫЙ
                    статистика.доступность >= 95.0 -> Палитра.ЖЁЛТЫЙ
                    else -> Палитра.КРАСНЫЙ
                },
            )
            СтрокаЗначения(
                л("Время ответа"),
                л(
                    "среднее {1} · максимум {2}",
                    форматЗадержка(статистика.среднееМс),
                    форматЗадержка(статистика.максимумМс),
                ),
            )
        }

        if (история.size >= 2) {
            Spacer(Modifier.height(6.dp))
            val точки = история.reversed().mapNotNull { она -> она.времяМс?.toFloat() }
            if (точки.size >= 2) {
                Спарклайн(значения = точки, цвет = цвет)
                Текст(
                    текст = л("Время ответа, последние {1} проверок", точки.size),
                    color = Палитра.ТУСКЛЫЙ,
                    style = MaterialTheme.typography.labelSmall,
                )
            } else {
                Спарклайн(
                    значения = история.reversed().map { она -> if (она.доступно) 1f else 0f },
                    цвет = цвет,
                    высота = 22.dp,
                )
                Текст(
                    текст = л("Последние {1} проверок", история.size),
                    color = Палитра.ТУСКЛЫЙ,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }

        if (проверка.время > 0) {
            Текст(
                текст = л("Проверено {1}", времяДата(проверка.время)),
                color = Палитра.ТУСКЛЫЙ,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

private fun итог(снимок: Снимок): String {
    val все = снимок.проверки.values
    if (все.isEmpty()) return л("нет данных")
    val сбоев = все.count { !it.доступно }
    return if (сбоев == 0) л("всё работает") else л("сбоев: {1}", сбоев)
}

private fun цветИтога(снимок: Снимок): androidx.compose.ui.graphics.Color {
    val все = снимок.проверки.values
    if (все.isEmpty()) return Палитра.ТУСКЛЫЙ
    return if (все.all { it.доступно }) Палитра.ЗЕЛЁНЫЙ else Палитра.КРАСНЫЙ
}
