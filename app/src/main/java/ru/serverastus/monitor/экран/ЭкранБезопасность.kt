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
import ru.serverastus.monitor.данные.времяДата
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.мониторинг.Движок

/** Вкладка «Безопасность»: fail2ban, ручные блокировки ufw и управление доступом. */
@Composable
fun ЭкранБезопасность(снимок: Снимок, движок: Движок) {
    val безопасность = снимок.безопасность
    var новыйIp by remember { mutableStateOf("") }
    var разблокировать by remember { mutableStateOf<String?>(null) }

    Прокрутка {
        Карточка(
            заголовок = л("Защита от подбора паролей"),
            подзаголовок = л("fail2ban — защита от подбора пароля по SSH"),
            справа = {
                val всего = безопасность?.всегоБлокировок ?: 0
                Ярлычок(
                    текст = if (всего > 0) л("блокировок: {1}", всего) else л("спокойно"),
                    цвет = if (всего > 0) Палитра.ЖЁЛТЫЙ else Палитра.ЗЕЛЁНЫЙ,
                )
            },
        ) {
            if (безопасность == null) {
                Пусто(л("Данные появятся после первого опроса безопасности"))
                return@Карточка
            }
            СтрокаЗначения(л("Всего попыток входа"), безопасность.всегоПопыток.toString())
            СтрокаЗначения(л("Всего блокировок"), безопасность.всегоБлокировок.toString())
            СтрокаЗначения(л("Заблокировано сейчас"), безопасность.забаненныеСейчас.size.toString())
            СтрокаЗначения(л("Обновлено"), времяДата(безопасность.время))

            if (безопасность.jails.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                безопасность.jails.forEach { jail ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Лампа(
                                цвет = if (jail.забаненные.isNotEmpty()) Палитра.ОРАНЖЕВЫЙ else Палитра.ЗЕЛЁНЫЙ,
                                пульсирует = true,
                            )
                            Spacer(Modifier.width(10.dp))
                            Текст(
                                текст = л("jail «{1}»", jail.имя),
                                color = Палитра.ТЕКСТ,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Текст(
                                текст = л("{1} попыток · {2} блок.", jail.текущиеПопытки, jail.текущиеБлокировки),
                                color = Палитра.ТУСКЛЫЙ,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        if (jail.забаненные.isNotEmpty()) {
                            Текст(
                                текст = jail.забаненные.joinToString(", "),
                                color = Палитра.ТУСКЛЫЙ,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        Карточка(
            заголовок = л("Активные блокировки"),
            подзаголовок = л("заблокированные вручную с этой вкладки IP-адреса"),
        ) {
            val блокировки = безопасность?.блокировки.orEmpty()
            if (блокировки.isEmpty()) {
                Пусто(л("Сейчас нет активных ручных блокировок."))
            } else {
                блокировки.forEach { правило ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Текст(
                            текст = правило.ip,
                            color = Палитра.ТЕКСТ,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Ярлычок(л("правило ufw №{1}", правило.номер), Палитра.ФИОЛЕТОВЫЙ)
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = { разблокировать = правило.ip }) { Текст(л("Разблокировать")) }
                    }
                }
            }
        }

        Карточка(
            заголовок = л("Заблокировать IP"),
            подзаголовок = л("полная блокировка адреса на сервере (все порты, через ufw)"),
        ) {
            OutlinedTextField(
                value = новыйIp,
                onValueChange = { новыйIp = it.filter { знак -> знак.isDigit() || знак == '.' || знак == ':' } },
                label = { Текст(л("IP-адрес")) },
                placeholder = { Текст(л("например, 203.0.113.5")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val адрес = новыйIp.trim()
                        if (адрес.isNotEmpty()) {
                            движок.заблокироватьIp(адрес)
                            новыйIp = ""
                        }
                    },
                    enabled = новыйIp.trim().length >= 7,
                ) { Текст(л("Заблокировать")) }
            }
            Spacer(Modifier.height(6.dp))
            Текст(
                текст = л("Проверьте, что это не ваш собственный IP — иначе вы потеряете доступ к серверу по SSH и к сайтам с этого адреса."),
                color = Палитра.ЖЁЛТЫЙ,
                style = MaterialTheme.typography.labelSmall,
            )
        }

        Карточка(
            заголовок = л("Последние события"),
            подзаголовок = л("кто пытался подобрать пароль по SSH и был заблокирован fail2ban"),
        ) {
            val события = безопасность?.события.orEmpty()
            if (события.isEmpty()) {
                Пусто(л("Событий ещё нет"))
            } else {
                события.takeLast(20).reversed().forEach { событие ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Текст(
                            текст = if (событие.действие == "ban") "⛔" else "✅",
                            color = Палитра.ТЕКСТ,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(Modifier.width(8.dp))
                        Текст(
                            текст = л("{1} · {2}", времяДата(событие.время), событие.ip),
                            color = if (событие.действие == "ban") Палитра.КРАСНЫЙ else Палитра.ЗЕЛЁНЫЙ,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Текст(
                            текст = событие.jail,
                            color = Палитра.ТУСКЛЫЙ,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }

    разблокировать?.let { адрес ->
        AlertDialog(
            onDismissRequest = { разблокировать = null },
            title = { Текст(л("Снять блокировку")) },
            text = { Текст(л("Разблокировать адрес {1} на сервере?", адрес)) },
            confirmButton = {
                TextButton(onClick = {
                    движок.разблокироватьIp(адрес)
                    разблокировать = null
                }) { Текст(л("Разблокировать")) }
            },
            dismissButton = { TextButton(onClick = { разблокировать = null }) { Текст(л("Отмена")) } },
            containerColor = Палитра.КАРТОЧКА,
            titleContentColor = Палитра.ТЕКСТ,
            textContentColor = Палитра.ТУСКЛЫЙ,
        )
    }
}
