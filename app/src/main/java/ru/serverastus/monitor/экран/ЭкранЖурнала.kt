package ru.serverastus.monitor.экран

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import ru.serverastus.monitor.данные.Локаль
import ru.serverastus.monitor.данные.Снимок
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.текст
import ru.serverastus.monitor.данные.форматБайт
import ru.serverastus.monitor.данные.полнаяДата
import ru.serverastus.monitor.данные.цельТекст
import ru.serverastus.monitor.мониторинг.Движок

/** Вкладка «Журнал»: события с уровнями и техжурнал приложения. */
@Composable
fun ЭкранЖурнала(снимок: Снимок, движок: Движок, отсечка: Long, наОтсечку: (Long) -> Unit) {
    var фильтр by remember { mutableStateOf("все") }
    var показатьТехжурнал by remember { mutableStateOf(false) }
    var обнулениеПодтверждается by remember { mutableStateOf(false) }

    val события = снимок.события
        .filter { it.время >= отсечка }
        .filter { фильтр == "все" || it.уровень == фильтр }
        .sortedByDescending { it.время }

    Прокрутка {
        Карточка(
            заголовок = л("Журнал событий"),
            справа = {
                val сбоев = снимок.события.count { it.уровень == "сбой" }
                Ярлычок(
                    текст = л("всего: {1} · сбоев: {2}", снимок.события.size, сбоев),
                    цвет = if (сбоев > 0) Палитра.КРАСНЫЙ else Палитра.ЗЕЛЁНЫЙ,
                )
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("все" to л("Все"), "сбой" to л("Сбои"), "предупреждение" to л("Тревоги"), "успех" to л("Успехи"))
                    .forEach { (ключ, подпись) ->
                        КнопкаФильтра(подпись = подпись, выбрана = фильтр == ключ) { фильтр = ключ }
                    }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { движок.обновитьСобытия() }) { Текст(л("Обновить")) }
                TextButton(onClick = { наОтсечку(System.currentTimeMillis() / 1000) }) { Текст(л("Очистить окно")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { обнулениеПодтверждается = true }) { Текст(л("Обнулить журнал")) }
            }
            Текст(
                текст = л("«Очистить окно» скрывает записи на экране, «Обнулить журнал» удаляет их из базы насовсем."),
                color = Палитра.ТУСКЛЫЙ,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(4.dp))
            if (события.isEmpty()) {
                Пусто(if (отсечка > 0) л("Окно очищено — ждём новых событий") else л("Событий пока нет"))
            } else {
                события.take(200).forEach { событие ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(18.dp)
                                    .background(Палитра.уровня(событие.уровень)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Текст(
                                текст = событие.цельТекст(),
                                color = Палитра.ТЕКСТ,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Текст(
                                текст = полнаяДата(событие.время),
                                color = Палитра.ТУСКЛЫЙ,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        Текст(
                            текст = событие.текст(),
                            color = Палитра.уровня(событие.уровень),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 11.dp, top = 2.dp),
                        )
                    }
                }
            }
        }

        Карточка(
            заголовок = л("Техжурнал приложения"),
            справа = {
                Ярлычок(л("строк: {1}", снимок.техжурнал.size), Палитра.ТУСКЛЫЙ)
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = { показатьТехжурнал = !показатьТехжурнал }) {
                    Текст(if (показатьТехжурнал) л("Свернуть") else л("Показать"))
                }
                TextButton(onClick = { движок.очиститьТехжурнал() }) { Текст(л("Обнулить")) }
            }
            if (показатьТехжурнал) {
                Spacer(Modifier.height(6.dp))
                val строки = снимок.техжурнал.takeLast(60)
                if (строки.isEmpty()) {
                    Пусто(л("Записей нет"))
                } else {
                    строки.reversed().forEach { строка ->
                        Текст(
                            текст = Локаль.перевестиСтрокуЖурнала(строка),
                            color = Палитра.ТУСКЛЫЙ,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        Карточка(заголовок = л("База истории")) {
            СтрокаЗначения(л("Файл"), движок.имяИстории)
            СтрокаЗначения(л("Размер"), форматБайт(снимок.размерБд))
            СтрокаЗначения(л("Хранить дней"), л("по настройке «хранить_дней»"))
            Текст(
                текст = л("Полная выгрузка истории — CSV-файлы в папке «экспорт» рядом с настройками."),
                color = Палитра.ТУСКЛЫЙ,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (обнулениеПодтверждается) {
        AlertDialog(
            onDismissRequest = { обнулениеПодтверждается = false },
            title = { Текст(л("Обнулить журнал событий?")) },
            text = {
                Текст(
                    л(
                        "Все записи журнала ({1} шт.) будут удалены из базы без возможности восстановления. Настройки и история метрик не затрагиваются.",
                        снимок.события.size,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    движок.обнулитьЖурнал()
                    наОтсечку(0L)
                    обнулениеПодтверждается = false
                }) { Текст(л("Да, обнулить")) }
            },
            dismissButton = {
                TextButton(onClick = { обнулениеПодтверждается = false }) { Текст(л("Отмена")) }
            },
            containerColor = Палитра.КАРТОЧКА,
            titleContentColor = Палитра.ТЕКСТ,
            textContentColor = Палитра.ТУСКЛЫЙ,
        )
    }
}

@Composable
private fun КнопкаФильтра(подпись: String, выбрана: Boolean, нажать: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (выбрана) Палитра.АКЦЕНТ.copy(alpha = 0.2f) else Палитра.ПАНЕЛЬ)
            .border(
                1.dp,
                if (выбрана) Палитра.АКЦЕНТ else Палитра.ГРАНИЦА,
                RoundedCornerShape(10.dp),
            )
            .clickable { нажать() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Текст(
            текст = подпись,
            color = if (выбрана) Палитра.АКЦЕНТ else Палитра.ТУСКЛЫЙ,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
