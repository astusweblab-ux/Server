package ru.serverastus.monitor.экран

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.serverastus.monitor.Монитор
import ru.serverastus.monitor.данные.Настройки
import ru.serverastus.monitor.данные.Снимок
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.полнаяДата
import ru.serverastus.monitor.данные.привестиСписокСлужб
import ru.serverastus.monitor.данные.форматБайт
import ru.serverastus.monitor.мониторинг.экспортCsv
import java.io.File

/** Изменяемое поле формы: правка сразу перерисовывает экран. */
private class Поле(начальное: String) {
    var значение by mutableStateOf(начальное)
}

private fun Поле.этоДа(): Boolean = значение == "да"

private fun полеДа(настройки: Настройки, узел: JSONObject, ключ: String, поумолчанию: Boolean): Поле =
    Поле(if (настройки.да(узел, ключ, поумолчанию)) "да" else "нет")

/** Строка списка сайтов в форме настроек. */
private class СтрокаСайта(имя: String, url: String, коды: String, ssl: Boolean, служба: String) {
    val имя = Поле(имя)
    val url = Поле(url)
    val коды = Поле(коды)
    val служба = Поле(служба)
    var проверятьSsl by mutableStateOf(ssl)
}

/** Строка списка портов в форме настроек. */
private class СтрокаПорта(имя: String, хост: String, порт: String) {
    val имя = Поле(имя)
    val хост = Поле(хост)
    val порт = Поле(порт)
}

/** Черновик настроек: файл config.json меняется только по кнопке «Сохранить настройки». */
private class Черновик(настройки: Настройки) {

    private val сервер: JSONObject = настройки.сервер
    private val уведомления: JSONObject = настройки.уведомления()
    private val телеграм: JSONObject = уведомления.optJSONObject("телеграм") ?: JSONObject()
    private val вебхук: JSONObject = уведомления.optJSONObject("вебхук") ?: JSONObject()

    val включен = полеДа(настройки, сервер, "включен", true)
    val хост = Поле(сервер.optString("хост", ""))
    val порт = Поле(настройки.целое(сервер, "порт", 22).toString())
    val пользователь = Поле(сервер.optString("пользователь", ""))
    // Пароль лежит в защищённом хранилище телефона, а не в config.json.
    val пароль = Поле(настройки.парольСервера())
    val файлКлюча = Поле(сервер.optString("файл_ключа", ""))
    val таймаут = Поле(настройки.целое(сервер, "таймаут_секунд", 20).toString())
    val дискПуть = Поле(сервер.optString("диск_путь", "/"))

    val интервалСервера = Поле(настройки.целое(сервер, "интервал_секунд", 5).toString())
    val интервалПодробностей = Поле(настройки.целое(сервер, "интервал_подробностей_секунд", 20).toString())
    val интервалБезопасности = Поле(настройки.целое(сервер, "интервал_безопасности_секунд", 30).toString())
    val интервалСети = Поле(настройки.целое(сервер, "интервал_сети_секунд", 30).toString())
    val интервалОбслуживания = Поле(настройки.целое(сервер, "интервал_обслуживания_секунд", 600).toString())
    val интервалСайтов = Поле(настройки.числоСервера("сайты_интервал_секунд", 10.0).toInt().toString())
    val интервалПортов = Поле(настройки.числоСервера("порты_интервал_секунд", 15.0).toInt().toString())
    val хранитьДней = Поле(настройки.числоСервера("хранить_дней", 14.0).toInt().toString())
    val запускатьПриЗагрузке = полеДа(настройки, настройки.данные, "запускать_при_загрузке", true)

    private val пороги = настройки.пороги()
    val порогЦп = Поле(пороги.цп.toInt().toString())
    val порогОзу = Поле(пороги.озу.toInt().toString())
    val порогДиск = Поле(пороги.диск.toInt().toString())

    val локальные = полеДа(настройки, уведомления, "локальные", true)
    val повторОповещения = Поле(настройки.целое(уведомления, "повтор_оповещения_минут", 30).toString())
    val телеграмВключен = полеДа(настройки, телеграм, "включен", false)
    val телеграмТокен = Поле(телеграм.optString("токен", ""))
    val телеграмЧат = Поле(телеграм.optString("чат_id", ""))
    val вебхукВключен = полеДа(настройки, вебхук, "включен", false)
    val вебхукАдрес = Поле(вебхук.optString("url", ""))

    val службы = mutableStateListOf<String>().apply { addAll(настройки.службы()) }

    val сайты = mutableStateListOf<СтрокаСайта>().apply {
        addAll(
            настройки.сайты().map { сайт ->
                СтрокаСайта(
                    имя = сайт.optString("имя", ""),
                    url = сайт.optString("url", ""),
                    коды = настройки.коды(сайт).joinToString(", "),
                    ssl = сайт.optBoolean("проверять_ssl", true),
                    служба = сайт.optString("служба", ""),
                )
            },
        )
    }

    val порты = mutableStateListOf<СтрокаПорта>().apply {
        addAll(
            настройки.порты().map { узел ->
                СтрокаПорта(
                    имя = узел.optString("имя", ""),
                    хост = узел.optString("хост", ""),
                    порт = настройки.целое(узел, "порт", 80).toString(),
                )
            },
        )
    }

    /** Собирает настройки из формы в тот же вид, что и config.json. */
    fun вJson(): JSONObject = JSONObject().apply {
        put("сайты_интервал_секунд", целое(интервалСайтов))
        put("порты_интервал_секунд", целое(интервалПортов))
        put("хранить_дней", целое(хранитьДней))
        put("запускать_при_загрузке", запускатьПриЗагрузке.этоДа())
        put(
            "сервер_linux",
            JSONObject()
                .put("включен", включен.этоДа())
                .put("хост", хост.значение.trim())
                .put("порт", целое(порт))
                .put("пользователь", пользователь.значение.trim())
                .put("пароль", пароль.значение)
                .put("файл_ключа", файлКлюча.значение.trim())
                .put("таймаут_секунд", целое(таймаут))
                .put("диск_путь", дискПуть.значение.trim().ifBlank { "/" })
                .put("интервал_секунд", целое(интервалСервера))
                .put("интервал_подробностей_секунд", целое(интервалПодробностей))
                .put("интервал_безопасности_секунд", целое(интервалБезопасности))
                .put("интервал_сети_секунд", целое(интервалСети))
                .put("интервал_обслуживания_секунд", целое(интервалОбслуживания))
                .put(
                    "пороги_предупреждений",
                    JSONObject()
                        .put("цп_процент", целое(порогЦп))
                        .put("озу_процент", целое(порогОзу))
                        .put("диск_процент", целое(порогДиск)),
                )
                .put("службы", JSONArray(привестиСписокСлужб(службы))),
        )
        put(
            "сайты",
            JSONArray().apply {
                сайты.filter { it.url.значение.isNotBlank() }.forEach { сайт ->
                    put(
                        JSONObject()
                            .put("имя", сайт.имя.значение.trim().ifBlank { сайт.url.значение.trim() })
                            .put("url", сайт.url.значение.trim())
                            .put("метод", "GET")
                            .put("ожидать_коды", JSONArray(кодыИз(сайт.коды.значение)))
                            .put("таймаут_секунд", 15)
                            .put("проверять_ssl", сайт.проверятьSsl)
                            .put("служба", сайт.служба.значение.trim()),
                    )
                }
            },
        )
        put(
            "порты",
            JSONArray().apply {
                порты.filter { it.хост.значение.isNotBlank() }.forEach { узел ->
                    put(
                        JSONObject()
                            .put("имя", узел.имя.значение.trim().ifBlank { узел.хост.значение.trim() })
                            .put("хост", узел.хост.значение.trim())
                            .put("порт", целое(узел.порт))
                            .put("таймаут_секунд", 5)
                            .put("служба", ""),
                    )
                }
            },
        )
        put(
            "уведомления",
            JSONObject()
                .put("локальные", локальные.этоДа())
                .put("повтор_оповещения_минут", целое(повторОповещения))
                .put(
                    "телеграм",
                    JSONObject()
                        .put("включен", телеграмВключен.этоДа())
                        .put("токен", телеграмТокен.значение.trim())
                        .put("чат_id", телеграмЧат.значение.trim()),
                )
                .put(
                    "вебхук",
                    JSONObject()
                        .put("включен", вебхукВключен.этоДа())
                        .put("url", вебхукАдрес.значение.trim()),
                ),
        )
    }

    private fun целое(поле: Поле): Int = поле.значение.trim().toIntOrNull() ?: 0

    private fun кодыИз(текст: String): List<Int> =
        текст.split(',', ';', ' ', '/').mapNotNull { it.trim().toIntOrNull() }.ifEmpty { listOf(200) }
}

/** Вкладка «Настройки»: подключение, интервалы, пороги, списки, уведомления, файлы. */
@Composable
fun ЭкранНастроек(
    приложение: Монитор,
    снимок: Снимок,
    наОткрытьМастер: () -> Unit = {},
    наОткрытьСерверы: () -> Unit = {},
) {
    var ключФормы by remember { mutableStateOf(0) }
    val контекст = LocalContext.current
    val область = rememberCoroutineScope()

    val выбратьФайл = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { адрес ->
        if (адрес != null) {
            val текст = try {
                контекст.contentResolver.openInputStream(адрес)?.use { поток ->
                    поток.readBytes().toString(Charsets.UTF_8)
                }
            } catch (_: Exception) {
                null
            }
            if (текст != null && приложение.настройки.импортировать(текст)) {
                приложение.движок.применитьНастройки()
                приложение.журнал(л("Настройки загружены из файла"))
                Toast.makeText(контекст, л("Настройки загружены"), Toast.LENGTH_SHORT).show()
                ключФормы++
            } else {
                Toast.makeText(контекст, л("Не удалось прочитать config.json"), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Черновик пересобирается и при смене сервера: у каждого профиля свои значения.
    key(ключФормы, приложение.настройки.активныйId) {
        ФормаНастроек(
            приложение = приложение,
            снимок = снимок,
            область = область,
            наИмпорт = { выбратьФайл.launch(arrayOf("application/json", "text/plain", "*/*")) },
            наПерезагрузкуФормы = { ключФормы++ },
            наОткрытьМастер = наОткрытьМастер,
            наОткрытьСерверы = наОткрытьСерверы,
        )
    }
}

@Composable
private fun ФормаНастроек(
    приложение: Монитор,
    снимок: Снимок,
    область: CoroutineScope,
    наИмпорт: () -> Unit,
    наПерезагрузкуФормы: () -> Unit,
    наОткрытьМастер: () -> Unit,
    наОткрытьСерверы: () -> Unit,
) {
    val контекст = LocalContext.current
    val черновик = remember { Черновик(приложение.настройки) }

    var замечания by remember { mutableStateOf<List<String>?>(null) }
    var показыватьСброс by remember { mutableStateOf(false) }
    var проверкаИдёт by remember { mutableStateOf(false) }
    var экспортИдёт by remember { mutableStateOf(false) }

    fun сохранить(тихо: Boolean) {
        приложение.настройки.применить(черновик.вJson())
        приложение.движок.применитьНастройки()
        приложение.журнал(л("Настройки сохранены"))
        if (!тихо) Toast.makeText(контекст, л("Настройки сохранены"), Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Карточка(
            заголовок = л("Связь с сервером"),
            справа = {
                Ярлычок(
                    текст = снимок.текстСвязи,
                    цвет = when (снимок.связь) {
                        true -> Палитра.ЗЕЛЁНЫЙ
                        false -> Палитра.КРАСНЫЙ
                        null -> Палитра.ТУСКЛЫЙ
                    },
                )
            },
        ) {
            СтрокаЗначения(л("Сервер"), снимок.имяСервера.ifBlank { "—" })
            СтрокаЗначения(л("Задержка отклика"), снимок.задержкаМс?.let { л("{1} мс", it) } ?: "—")
            СтрокаЗначения(
                л("Последний опрос"),
                if (снимок.последнийОпрос > 0) полнаяДата(снимок.последнийОпрос) else л("ещё не было"),
            )
            СтрокаЗначения(л("Размер истории"), форматБайт(снимок.размерБд))
            РядКнопок(
                listOf(
                    л("Мастер настройки") to наОткрытьМастер,
                    л("Серверы") to наОткрытьСерверы,
                    (if (проверкаИдёт) л("Проверяю…") else л("Проверить связь")) to {
                        область.launch {
                            проверкаИдёт = true
                            сохранить(тихо = true)
                            val ответ = приложение.движок.проверитьПодключение()
                            проверкаИдёт = false
                            приложение.журнал(л("Проверка связи: {1}", ответ.second))
                            Toast.makeText(
                                контекст,
                                if (ответ.first) л("Связь есть. {1}", ответ.second) else л("Ошибка: {1}", ответ.second),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                ),
            )
            Подсказка(л("Кнопка сначала сохраняет настройки, а затем проверяет подключение по SSH."))
        }

        Карточка(л("Подключение к серверу по SSH")) {
            Переключатель(л("Мониторить сервер"), черновик.включен)
            ПолеВвода(л("Адрес или домен"), черновик.хост)
            ПолеВвода(л("Порт SSH"), черновик.порт, числовое = true)
            ПолеВвода(л("Пользователь"), черновик.пользователь)
            ПолеВвода(л("Пароль"), черновик.пароль, секрет = true)
            ПолеВвода(л("Файл ключа (путь на телефоне)"), черновик.файлКлюча)
            ПолеВвода(л("Таймаут подключения, секунд"), черновик.таймаут, числовое = true)
            ПолеВвода(л("Точка монтирования диска"), черновик.дискПуть)
            Подсказка(
                л("Пароль хранится в защищённом хранилище телефона, а не в config.json: в файле остаётся только ссылка на секрет. Файл ключа нужно положить в каталог приложения."),
            )
        }

        Карточка(л("Интервалы опроса, секунд")) {
            ПолеВвода(л("Метрики сервера"), черновик.интервалСервера, числовое = true)
            ПолеВвода(л("Подробные данные (процессы, температура)"), черновик.интервалПодробностей, числовое = true)
            ПолеВвода(л("Безопасность (fail2ban, ufw)"), черновик.интервалБезопасности, числовое = true)
            ПолеВвода(л("Диагностика сети (TCP и UDP)"), черновик.интервалСети, числовое = true)
            ПолеВвода(л("Обслуживание сервера (обновления, журналы, диски)"), черновик.интервалОбслуживания, числовое = true)
            ПолеВвода(л("Сайты"), черновик.интервалСайтов, числовое = true)
            ПолеВвода(л("Порты"), черновик.интервалПортов, числовое = true)
            ПолеВвода(л("Хранить историю, дней"), черновик.хранитьДней, числовое = true)
            Переключатель(л("Запускать мониторинг при загрузке телефона"), черновик.запускатьПриЗагрузке)
        }

        Карточка(л("Пороги предупреждений, %")) {
            ПолеВвода(л("Загрузка процессора"), черновик.порогЦп, числовое = true)
            ПолеВвода(л("Занятая память"), черновик.порогОзу, числовое = true)
            ПолеВвода(л("Занятое место на диске"), черновик.порогДиск, числовое = true)
            Подсказка(
                л("Когда значение превысит порог, в журнал попадёт предупреждение, а если включены уведомления — придёт сообщение на телефон."),
            )
        }

        Карточка(л("Службы systemd: {1}", черновик.службы.size)) {
            черновик.службы.forEachIndexed { индекс, служба ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedTextField(
                        value = служба,
                        onValueChange = { черновик.службы[индекс] = it },
                        label = { Текст(л("Имя службы")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { черновик.службы.removeAt(индекс) }) { Текст(л("Удалить")) }
                }
            }
            РядКнопок(listOf(л("Добавить службу") to { черновик.службы.add("") }))
            Подсказка(л("Имена как в systemctl, например: nginx, mysql, ssh, fail2ban."))
        }

        Карточка(л("Сайты: {1}", черновик.сайты.size)) {
            черновик.сайты.forEachIndexed { индекс, сайт ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    ПолеВвода(л("Имя"), сайт.имя)
                    ПолеВвода(л("Адрес (URL)"), сайт.url)
                    ПолеВвода(л("Ожидаемые коды через запятую"), сайт.коды)
                    ПолеВвода(л("Служба, связанная с сайтом"), сайт.служба)
                    ПереключательЗначение(
                        подпись = л("Проверять срок SSL-сертификата"),
                        включено = сайт.проверятьSsl,
                        наИзменение = { сайт.проверятьSsl = it },
                    )
                    РядКнопок(listOf(л("Удалить сайт") to { черновик.сайты.removeAt(индекс) }))
                }
            }
            РядКнопок(
                listOf(
                    л("Добавить сайт") to {
                        черновик.сайты.add(СтрокаСайта("", "https://", "200, 301, 302", true, ""))
                    },
                ),
            )
        }

        Карточка(л("Порты: {1}", черновик.порты.size)) {
            черновик.порты.forEachIndexed { индекс, узел ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    ПолеВвода(л("Имя"), узел.имя)
                    ПолеВвода(л("Хост"), узел.хост)
                    ПолеВвода(л("Порт"), узел.порт, числовое = true)
                    РядКнопок(listOf(л("Удалить порт") to { черновик.порты.removeAt(индекс) }))
                }
            }
            РядКнопок(
                listOf(
                    л("Добавить порт") to {
                        черновик.порты.add(СтрокаПорта("", черновик.хост.значение.trim(), "80"))
                    },
                ),
            )
        }

        Карточка(л("Уведомления")) {
            Переключатель(л("Показывать уведомления на телефоне"), черновик.локальные)
            ПолеВвода(л("Не повторять одно событие, минут"), черновик.повторОповещения, числовое = true)
            Переключатель(л("Отправлять в Telegram"), черновик.телеграмВключен)
            ПолеВвода(л("Токен бота"), черновик.телеграмТокен, секрет = true)
            ПолеВвода(л("Чат (chat_id)"), черновик.телеграмЧат)
            Переключатель(л("Отправлять на вебхук"), черновик.вебхукВключен)
            ПолеВвода(л("Адрес вебхука"), черновик.вебхукАдрес)
            РядКнопок(
                listOf(
                    л("Проверить Telegram") to {
                        область.launch {
                            val ошибка = withContext(Dispatchers.IO) {
                                приложение.оповещения.проверитьТелеграм(
                                    черновик.телеграмТокен.значение.trim(),
                                    черновик.телеграмЧат.значение.trim(),
                                )
                            }
                            Toast.makeText(
                                контекст,
                                if (ошибка == null) л("Тестовое сообщение отправлено") else л("Ошибка: {1}", ошибка),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                    л("Проверить вебхук") to {
                        область.launch {
                            val ошибка = withContext(Dispatchers.IO) {
                                приложение.оповещения.проверитьВебхук(черновик.вебхукАдрес.значение.trim())
                            }
                            Toast.makeText(
                                контекст,
                                if (ошибка == null) л("Тестовое событие отправлено") else л("Ошибка: {1}", ошибка),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    },
                ),
            )
        }

        Карточка(л("Файлы и действия")) {
            СтрокаЗначения(л("Каталог приложения"), приложение.настройки.каталог.absolutePath)
            СтрокаЗначения(л("Файл настроек"), приложение.настройки.файл.name)
            // У каждого сервера свои история и техжурнал — их видно, чтобы не спутать.
            СтрокаЗначения(л("История сервера"), приложение.настройки.файлИстории())
            СтрокаЗначения(л("Техжурнал сервера"), приложение.настройки.файлЖурнала())
            Подсказка(
                л("Скопируйте config.json из настольной версии в каталог приложения (по USB) и нажмите «Загрузить из файла» — подтянутся адрес, пароль, сайты, порты и службы."),
            )
            РядКнопок(
                listOf(
                    л("Сохранить настройки") to {
                        val список = приложение.настройки.проверить(черновик.вJson())
                        if (список.isEmpty()) сохранить(тихо = false) else замечания = список
                    },
                    л("Отменить правки") to наПерезагрузкуФормы,
                ),
            )
            РядКнопок(
                listOf(
                    л("Сохранить в файл") to {
                        сохранить(тихо = true)
                        поделиться(
                            контекст,
                            приложение.настройки.файл,
                            "application/json",
                            л("Настройки ServerMonitor"),
                        )
                    },
                    л("Загрузить из файла") to наИмпорт,
                ),
            )
            РядКнопок(
                listOf(
                    (if (экспортИдёт) л("Выгружаю…") else л("Экспорт CSV")) to {
                        область.launch {
                            экспортИдёт = true
                            val результат = withContext(Dispatchers.IO) {
                                try {
                                    экспортCsv(приложение.история, приложение.настройки.каталог)
                                } catch (_: Exception) {
                                    null
                                }
                            }
                            экспортИдёт = false
                            if (результат == null) {
                                Toast.makeText(контекст, л("Экспорт не удался"), Toast.LENGTH_LONG).show()
                            } else {
                                приложение.журнал(
                                    л(
                                        "Экспорт CSV: {1} строк метрик, {2} строк проверок",
                                        результат.строкМетрик,
                                        результат.строкПроверок,
                                    ),
                                )
                                Toast.makeText(
                                    контекст,
                                    л("Готово: файлов {1} в папке «экспорт»", результат.файлы.size),
                                    Toast.LENGTH_LONG,
                                ).show()
                                поделиться(контекст, результат.файлы.first(), "text/csv", л("История мониторинга"))
                            }
                        }
                    },
                    л("Сбросить настройки") to { показыватьСброс = true },
                ),
            )
            Подсказка(л("Экспорт создаёт «метрики.csv» и «проверки.csv» в папке «экспорт» каталога приложения."))
        }

        Карточка(л("О приложении")) {
            // версию берём из манифеста, чтобы она не расходилась с versionName в сборке
            val версияСборки = remember(контекст) {
                runCatching {
                    контекст.packageManager.getPackageInfo(контекст.packageName, 0).versionName
                }.getOrNull() ?: "—"
            }
            СтрокаЗначения(л("Программа"), л("ServerMonitor для Android"))
            СтрокаЗначения(л("Версия"), версияСборки)
            СтрокаЗначения(л("Ядро"), л("совместимо с настольной версией"))
            СтрокаЗначения(л("Разработчик"), РАЗРАБОТЧИК)
            СтрокаЗначения(л("Сайт"), "astuslab.com.ua", цвет = Палитра.АКЦЕНТ)
            РядКнопок(
                listOf(
                    л("Открыть сайт разработчика") to { открытьСсылку(контекст, САЙТ_РАЗРАБОТЧИКА) },
                    л("Мастер настройки") to наОткрытьМастер,
                ),
            )
            Подсказка(
                л("Мобильная версия повторяет настольную: те же интервалы, пороги, тексты событий и формат истории."),
            )
            Подсказка(
                л("В приложении нет чужих настроек: адрес сервера, сайты, порты и службы задаёт владелец телефона — через мастер настройки или вручную на этой вкладке."),
            )
        }
    }

    замечания?.let { список ->
        AlertDialog(
            onDismissRequest = { замечания = null },
            title = { Текст(л("Проверьте настройки")) },
            text = { Текст(список.joinToString("\n") { "• $it" }) },
            confirmButton = {
                TextButton(onClick = {
                    сохранить(тихо = false)
                    замечания = null
                }) { Текст(л("Сохранить всё равно")) }
            },
            dismissButton = { TextButton(onClick = { замечания = null }) { Текст(л("Исправить")) } },
            containerColor = Палитра.КАРТОЧКА,
            titleContentColor = Палитра.ТЕКСТ,
            textContentColor = Палитра.ТУСКЛЫЙ,
        )
    }

    if (показыватьСброс) {
        AlertDialog(
            onDismissRequest = { показыватьСброс = false },
            title = { Текст(л("Сбросить настройки?")) },
            text = { Текст(л("Адреса, пароль и списки будут заменены значениями по умолчанию. После сброса откроется мастер настройки.")) },
            confirmButton = {
                TextButton(onClick = {
                    приложение.настройки.сбросить()
                    приложение.движок.применитьНастройки()
                    приложение.журнал(л("Настройки сброшены к значениям по умолчанию"))
                    показыватьСброс = false
                    наПерезагрузкуФормы()
                    наОткрытьМастер()
                }) { Текст(л("Сбросить")) }
            },
            dismissButton = { TextButton(onClick = { показыватьСброс = false }) { Текст(л("Отмена")) } },
            containerColor = Палитра.КАРТОЧКА,
            titleContentColor = Палитра.ТЕКСТ,
            textContentColor = Палитра.ТУСКЛЫЙ,
        )
    }
}

@Composable
private fun ПолеВвода(
    подпись: String,
    поле: Поле,
    числовое: Boolean = false,
    секрет: Boolean = false,
) {
    OutlinedTextField(
        value = поле.значение,
        onValueChange = { поле.значение = it },
        label = { Текст(подпись) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (числовое) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (секрет) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}

@Composable
private fun Переключатель(подпись: String, поле: Поле) {
    ПереключательЗначение(
        подпись = подпись,
        включено = поле.этоДа(),
        наИзменение = { поле.значение = if (it) "да" else "нет" },
    )
}

@Composable
private fun ПереключательЗначение(подпись: String, включено: Boolean, наИзменение: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Текст(
            текст = подпись,
            color = Палитра.ТЕКСТ,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = включено, onCheckedChange = наИзменение)
    }
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

@Composable
private fun Подсказка(текст: String) {
    Текст(
        текст = текст,
        color = Палитра.ТУСКЛЫЙ,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Отправляет файл в другое приложение (почта, мессенджер, облачное хранилище). */
private fun поделиться(контекст: Context, файл: File, тип: String, заголовок: String) {
    if (!файл.isFile) {
        Toast.makeText(контекст, л("Файл ещё не создан: {1}", файл.name), Toast.LENGTH_LONG).show()
        return
    }
    try {
        val адрес: Uri = FileProvider.getUriForFile(контекст, л("{1}.files", контекст.packageName), файл)
        val намерение = Intent(Intent.ACTION_SEND).apply {
            type = тип
            putExtra(Intent.EXTRA_STREAM, адрес)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        контекст.startActivity(Intent.createChooser(намерение, заголовок))
    } catch (ошибка: Exception) {
        Toast.makeText(контекст, л("Не удалось поделиться файлом: {1}", ошибка.message), Toast.LENGTH_LONG).show()
    }
}
