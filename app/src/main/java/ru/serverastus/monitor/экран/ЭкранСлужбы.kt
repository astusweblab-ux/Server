package ru.serverastus.monitor.экран

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.serverastus.monitor.данные.Снимок
import ru.serverastus.monitor.данные.Служба
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.мониторинг.Движок

/** Вкладка «Службы»: состояние systemd-служб и кнопки управления ими. */
@Composable
fun ЭкранСлужбы(снимок: Снимок, движок: Движок) {
    var подтверждение by remember { mutableStateOf<Pair<Служба, String>?>(null) }

    Прокрутка {
        Карточка(
            заголовок = л("Службы сервера"),
            справа = {
                val работают = снимок.службы.count { it.работает }
                Ярлычок(
                    текст = л("{1} из {2}", работают, снимок.службы.size),
                    цвет = if (снимок.службы.isNotEmpty() && работают == снимок.службы.size) Палитра.ЗЕЛЁНЫЙ else Палитра.ЖЁЛТЫЙ,
                )
            },
        ) {
            if (снимок.службы.isEmpty()) {
                Пусто(л("Список служб пуст или ещё не получен"))
            } else {
                снимок.службы.forEachIndexed { индекс, служба ->
                    if (индекс > 0) Spacer(Modifier.height(12.dp))
                    СтрокаСлужбы(
                        служба = служба,
                        идётДействие = снимок.действиеИдёт == служба.имя,
                        нажать = { действие -> подтверждение = служба to действие },
                    )
                }
            }
        }

        КарточкаПодсказка(снимок)
    }

    подтверждение?.let { (служба, действие) ->
        val слово = when (действие) {
            "start" -> л("запустить")
            "stop" -> л("остановить")
            else -> л("перезапустить")
        }
        AlertDialog(
            onDismissRequest = { подтверждение = null },
            title = { Текст(л("Подтвердите действие")) },
            text = { Текст(л("{1} службу «{2}» на сервере?", слово, служба.имя)) },
            confirmButton = {
                TextButton(onClick = {
                    when (действие) {
                        "start" -> движок.запуститьСлужбу(служба.имя)
                        "stop" -> движок.остановитьСлужбу(служба.имя)
                        else -> движок.перезапуститьСлужбу(служба.имя)
                    }
                    подтверждение = null
                }) { Текст(л("Да, {1}", слово)) }
            },
            dismissButton = {
                TextButton(onClick = { подтверждение = null }) { Текст(л("Отмена")) }
            },
            containerColor = Палитра.КАРТОЧКА,
            titleContentColor = Палитра.ТЕКСТ,
            textContentColor = Палитра.ТУСКЛЫЙ,
        )
    }
}

@Composable
private fun КарточкаПодсказка(снимок: Снимок) {
    val идёт = снимок.действиеИдёт
    if (идёт != null) {
        Карточка(заголовок = л("Выполняется")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Крутилка()
                Spacer(Modifier.width(10.dp))
                Текст(
                    текст = л("Команда для «{1}» отправлена на сервер…", идёт),
                    color = Палитра.АКЦЕНТ,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun СтрокаСлужбы(служба: Служба, идётДействие: Boolean, нажать: (String) -> Unit) {
    val цвет = when {
        служба.работает -> Палитра.ЗЕЛЁНЫЙ
        служба.состояние == "failed" -> Палитра.КРАСНЫЙ
        else -> Палитра.ЖЁЛТЫЙ
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Лампа(цвет = цвет, пульсирует = служба.работает, периодМс = if (служба.работает) 2800 else 1300)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Текст(служба.имя, color = Палитра.ТЕКСТ, style = MaterialTheme.typography.titleMedium)
                Текст(
                    текст = служба.описание.ifBlank { служба.сМомента.ifBlank { "—" } },
                    color = Палитра.ТУСКЛЫЙ,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            if (идётДействие) Крутилка() else Ярлычок(служба.состояниеТекст, цвет)
        }

        if (служба.сМомента.isNotBlank() && служба.описание.isNotBlank()) {
            Текст(
                текст = л("с {1}", служба.сМомента),
                color = Палитра.ТУСКЛЫЙ,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = { нажать("start") }, enabled = !идётДействие) { Текст(л("Запустить")) }
            TextButton(onClick = { нажать("restart") }, enabled = !идётДействие) { Текст(л("Перезапустить")) }
            TextButton(onClick = { нажать("stop") }, enabled = !идётДействие) { Текст(л("Остановить")) }
        }
    }
}
