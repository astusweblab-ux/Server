package ru.serverastus.monitor.экран

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import ru.serverastus.monitor.Монитор
import ru.serverastus.monitor.данные.Настройки
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.полнаяДата

/**
 * Управление серверами: список профилей с состоянием связи и действиями
 * «открыть», «изменить», «удалить» и «добавить сервер». Данные каждого сервера
 * лежат в его профиле, поэтому переключение здесь ничего не смешивает.
 */
@Composable
fun ЭкранСерверов(
    приложение: Монитор,
    наНазад: () -> Unit,
    наОткрыть: (String) -> Unit,
    наИзменить: (String) -> Unit,
    наДобавить: () -> Unit,
) {
    val настройки = приложение.настройки
    // Список перечитывается после каждого действия: профили живут в файле настроек.
    var обновление by remember { mutableStateOf(0) }
    var кУдалению by remember { mutableStateOf<JSONObject?>(null) }
    val серверы = remember(обновление) { настройки.серверы }
    val активный = настройки.активныйId

    BackHandler { наНазад() }

    Scaffold(containerColor = Палитра.ФОН) { отступы ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(отступы)
                .padding(horizontal = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Текст(л("Серверы"), color = Палитра.ТЕКСТ, style = MaterialTheme.typography.titleMedium)
                    Текст(
                        текст = if (серверы.isEmpty()) {
                            л("Список пуст")
                        } else {
                            л("Сохранено серверов: {1}", серверы.size)
                        },
                        color = Палитра.ТУСКЛЫЙ,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                TextButton(onClick = наНазад) { Текст(л("Назад")) }
            }

            Column(modifier = Modifier.weight(1f)) {
                Прокрутка {
                    if (серверы.isEmpty()) {
                        Карточка(л("Серверов пока нет")) {
                            Текст(
                                текст = л("Добавьте сервер мастером настройки: приложение проверит подключение и найдёт на нём сайты, порты и службы."),
                                color = Палитра.ТУСКЛЫЙ,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    серверы.forEach { узел ->
                        val ид = настройки.текст(узел, "id")
                        КарточкаСервера(
                            настройки = настройки,
                            узел = узел,
                            активен = ид == активный,
                            наОткрыть = { наОткрыть(ид) },
                            наИзменить = { наИзменить(ид) },
                            наУдалить = { кУдалению = узел },
                        )
                    }

                    Карточка {
                        РядКнопок(
                            listOf(
                                л("+ Добавить сервер") to наДобавить,
                                л("Вернуться к мониторингу") to наНазад,
                            ),
                        )
                        Текст(
                            текст = л("«Добавить сервер» открывает мастер настройки: он проверяет подключение и ищет сайты, порты и службы. Настройки уже добавленных серверов при этом не меняются."),
                            color = Палитра.ТУСКЛЫЙ,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    кУдалению?.let { узел ->
        val ид = настройки.текст(узел, "id")
        val последний = серверы.size <= 1
        AlertDialog(
            onDismissRequest = { кУдалению = null },
            title = { Текст(л("Удалить сервер?"), color = Палитра.ТЕКСТ) },
            text = {
                Column {
                    Текст(
                        текст = л("Профиль «{1}» будет удалён.", настройки.подпись(ид)),
                        color = Палитра.ТЕКСТ,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Текст(
                        текст = л("История и журнал этого сервера останутся на диске: если добавить его снова, данные вернутся."),
                        color = Палитра.ТУСКЛЫЙ,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (последний) {
                        Текст(
                            текст = л("Это последний сервер: после удаления показывать будет нечего, и приложение снова откроет мастер настройки."),
                            color = Палитра.ЖЁЛТЫЙ,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val былАктивным = ид == активный
                        настройки.удалитьСервер(ид)
                        кУдалению = null
                        обновление++
                        // Движок перезапускаем только если удалили показываемый сервер.
                        if (былАктивным) приложение.обновитьСервер()
                        приложение.журнал(л("Сервер удалён: {1}", настройки.подпись(ид)))
                    },
                ) { Текст(л("Удалить"), color = Палитра.КРАСНЫЙ) }
            },
            dismissButton = {
                TextButton(onClick = { кУдалению = null }) { Текст(л("Отмена")) }
            },
        )
    }
}

@Composable
private fun КарточкаСервера(
    настройки: Настройки,
    узел: JSONObject,
    активен: Boolean,
    наОткрыть: () -> Unit,
    наИзменить: () -> Unit,
    наУдалить: () -> Unit,
) {
    val ид = настройки.текст(узел, "id")
    val состояние = настройки.состояние(ид)
    val подключение = настройки.последнееПодключение(ид)
    val ошибка = настройки.последняяОшибка(ид)
    val хост = настройки.текст(узел, "хост").ifBlank { л("адрес не задан") }

    Карточка(
        заголовок = настройки.подпись(ид).ifBlank { л("Сервер без имени") },
        подзаголовок = л("{1} · {2}:{3}", настройки.текст(узел, "пользователь").ifBlank { л("логин не задан") }, хост, настройки.целое(узел, "порт", 22)),
        справа = { Лампа(цвет = цветСостояния(состояние), размер = 12.dp) },
    ) {
        if (активен) {
            Spacer(Modifier.width(2.dp))
            Ярлычок(л("показан сейчас"), Палитра.АКЦЕНТ)
        }
        СтрокаЗначения(
            подпись = л("Состояние"),
            значение = состояние.ifBlank { л("нет данных") },
            цвет = цветСостояния(состояние),
        )
        if (подключение > 0) {
            СтрокаЗначения(л("Последняя проверка"), полнаяДата(подключение))
        }
        if (ошибка.isNotEmpty()) {
            СтрокаЗначения(л("Ошибка связи"), ошибка, цвет = Палитра.ЖЁЛТЫЙ)
        }
        СтрокаЗначения(
            л("Сайтов"),
            л("{1} · портов {2} · служб {3}", настройки.сайты(ид).size, настройки.порты(ид).size, настройки.службы(ид).size),
        )
        Spacer(Modifier.width(2.dp))
        РядКнопок(
            buildList {
                if (!активен) add(л("Открыть") to наОткрыть)
                add(л("Изменить") to наИзменить)
                add(л("Удалить") to наУдалить)
            },
        )
    }
}

/** Цвет точки состояния сервера. */
private fun цветСостояния(состояние: String): Color = when (состояние) {
    "онлайн" -> Палитра.ЗЕЛЁНЫЙ
    "недоступен" -> Палитра.КРАСНЫЙ
    else -> Палитра.ТУСКЛЫЙ
}

@Composable
private fun РядКнопок(кнопки: List<Pair<String, () -> Unit>>) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        кнопки.forEach { пара ->
            TextButton(onClick = пара.second) { Текст(пара.first) }
        }
    }
}
