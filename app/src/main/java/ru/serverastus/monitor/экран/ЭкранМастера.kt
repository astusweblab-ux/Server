package ru.serverastus.monitor.экран

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.serverastus.monitor.Монитор
import ru.serverastus.monitor.данные.Настройки
import ru.serverastus.monitor.данные.добавитьБезДубля
import ru.serverastus.monitor.данные.добавитьСлужбу
import ru.serverastus.monitor.данные.естьСлужба
import ru.serverastus.monitor.данные.естьЭлемент
import ru.serverastus.monitor.данные.ключАдреса
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.привестиСписокСлужб
import ru.serverastus.monitor.мониторинг.СлужбаМониторинга
import ru.serverastus.monitor.сеть.LinuxServer
import ru.serverastus.monitor.сеть.ПараметрыСервера
import ru.serverastus.monitor.сеть.безШумаСлужб

/** Разработчик приложения — показывается в мастере и в разделе «О приложении». */
const val РАЗРАБОТЧИК = "ASTUS LAB WEB STUDIO"
const val САЙТ_РАЗРАБОТЧИКА = "https://astuslab.com.ua"

// Названия шагов берутся при каждом обращении: обычный `val` застыл бы на языке,
// который был при первой загрузке класса.
private val ШАГИ: List<String>
    get() = listOf(л("Приветствие"), л("Сервер"), л("Сайты"), л("Порты"), л("Службы"), л("Готово"))

/** Службы, которые чаще всего и нужны: остальные показываются ниже. */
private val ЧАСТЫЕ_СЛУЖБЫ = listOf(
    "nginx", "apache2", "httpd", "mysql", "mariadb", "postgresql", "redis-server", "redis",
    "docker", "fail2ban", "ssh", "sshd", "cron", "php-fpm", "gunicorn", "supervisor", "ufw",
)

private val ИМЕНА_ПОРТОВ = mapOf(
    21 to "FTP", 22 to "SSH", 25 to "SMTP", 53 to "DNS", 80 to "HTTP", 110 to "POP3", 143 to "IMAP",
    443 to "HTTPS", 465 to "SMTPS", 587 to "SMTP", 993 to "IMAPS", 995 to "POP3S", 3000 to "Node.js",
    3306 to "MySQL", 5432 to "PostgreSQL", 6379 to "Redis", 8000 to л("HTTP-альт."), 8080 to л("HTTP-альт."),
    8443 to л("HTTPS-альт."), 9000 to л("Панель"), 27017 to "MongoDB",
)

/** Открывает ссылку во внешнем браузере (сайт разработчика, справка). */
fun открытьСсылку(контекст: Context, адрес: String) {
    try {
        контекст.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(адрес)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        Toast.makeText(контекст, л("Не удалось открыть ссылку: {1}", адрес), Toast.LENGTH_LONG).show()
    }
}

/** Сайт в мастере: адрес и подпись для истории. */
private data class СайтМастера(val имя: String, val url: String)

/** Порт в мастере: номер и подпись. */
private data class ПортМастера(val имя: String, val порт: Int)

/**
 * Мастер настройки: пользователь сам вводит свой сервер, а приложение
 * умеет найти на нём сайты (nginx/Apache), слушающие порты и службы systemd.
 * Никаких чужих данных в приложении нет — файл настроек создаёт сам пользователь.
 *
 * Обычный запуск (без параметров) правит профиль активного сервера. С
 * `новыйСервер = true` мастер собирает отдельный профиль с нуля — настройки
 * уже добавленных серверов при этом не меняются; с `идСервера` он правит
 * конкретный существующий профиль.
 */
@Composable
fun ЭкранМастера(
    приложение: Монитор,
    наГотово: () -> Unit,
    идСервера: String? = null,
    новыйСервер: Boolean = false,
) {
    val контекст = LocalContext.current
    val область = rememberCoroutineScope()
    // Новый сервер начинается с пустого профиля: чужие адреса и пароли в форму не попадают.
    val сервер = remember {
        if (новыйСервер) Настройки.умолчанияСервера() else приложение.настройки.узелСервера(идСервера)
    }

    var шаг by remember { mutableStateOf(0) }

    var имяСервера by remember { mutableStateOf(приложение.настройки.текст(сервер, "имя")) }
    var включен by remember { mutableStateOf(приложение.настройки.да(сервер, "включен", true)) }
    var хост by remember { mutableStateOf(приложение.настройки.текст(сервер, "хост")) }
    var порт by remember { mutableStateOf(приложение.настройки.целое(сервер, "порт", 22).toString()) }
    var пользователь by remember { mutableStateOf(приложение.настройки.текст(сервер, "пользователь")) }
    var пароль by remember {
        mutableStateOf(if (новыйСервер) "" else приложение.настройки.парольСервера(идСервера))
    }
    var файлКлюча by remember { mutableStateOf(приложение.настройки.текст(сервер, "файл_ключа")) }

    val сайты = remember {
        mutableStateListOf<СайтМастера>().apply {
            if (новыйСервер) return@apply
            addAll(
                приложение.настройки.сайты(идСервера).map { узел ->
                    СайтМастера(
                        имя = приложение.настройки.текст(узел, "имя"),
                        url = приложение.настройки.текст(узел, "url"),
                    )
                },
            )
        }
    }
    val порты = remember {
        mutableStateListOf<ПортМастера>().apply {
            if (новыйСервер) return@apply
            addAll(
                приложение.настройки.порты(идСервера).map { узел ->
                    ПортМастера(
                        имя = приложение.настройки.текст(узел, "имя"),
                        порт = приложение.настройки.целое(узел, "порт", 80),
                    )
                },
            )
        }
    }
    val службы = remember {
        mutableStateListOf<String>().apply {
            if (!новыйСервер) addAll(приложение.настройки.службы(идСервера))
        }
    }

    var новыйАдрес by remember { mutableStateOf("") }
    var новыйПорт by remember { mutableStateOf("") }
    var новаяСлужба by remember { mutableStateOf("") }

    var идётПроверка by remember { mutableStateOf(false) }
    var ответПроверки by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var идётПоиск by remember { mutableStateOf(false) }
    var ошибкаПоиска by remember { mutableStateOf<String?>(null) }
    var найденыСайты by remember { mutableStateOf<List<String>>(emptyList()) }
    var найденыПорты by remember { mutableStateOf<List<Int>>(emptyList()) }
    var найденыСлужбы by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var есть443 by remember { mutableStateOf(false) }
    var искалиСайты by remember { mutableStateOf(false) }
    var искалиСлужбы by remember { mutableStateOf(false) }

    fun параметры(): ПараметрыСервера = ПараметрыСервера(
        хост = хост.trim(),
        порт = порт.trim().toIntOrNull() ?: 22,
        пользователь = пользователь.trim(),
        пароль = пароль,
        файлКлюча = файлКлюча.trim(),
        таймаутСекунд = приложение.настройки.целое(сервер, "таймаут_секунд", 20),
        дискПуть = приложение.настройки.текст(сервер, "диск_путь").ifBlank { "/" },
    )

    fun готово(): JSONObject = JSONObject().apply {
        put("имя", имяСервера.trim())
        put("мастер_пройден", true)
        put("включен", включен)
        put("хост", хост.trim())
        put("порт", порт.trim().toIntOrNull() ?: 22)
        put("пользователь", пользователь.trim())
        put("пароль", пароль)
        put("файл_ключа", файлКлюча.trim())
        put("службы", JSONArray(привестиСписокСлужб(службы)))
        put(
            "сайты",
            JSONArray().apply {
                сайты.filter { it.url.isNotBlank() }.forEach { сайт ->
                    put(
                        JSONObject()
                            .put("имя", сайт.имя.trim().ifBlank { сайт.url.trim() })
                            .put("url", сайт.url.trim())
                            .put("метод", "GET")
                            .put("ожидать_коды", JSONArray(listOf(200, 301, 302)))
                            .put("таймаут_секунд", 15)
                            .put("проверять_ssl", true)
                            .put("служба", ""),
                    )
                }
            },
        )
        put(
            "порты",
            JSONArray().apply {
                порты.filter { хост.trim().isNotEmpty() }.forEach { узел ->
                    put(
                        JSONObject()
                            .put("имя", узел.имя.trim().ifBlank { л("Порт {1}", узел.порт) })
                            .put("хост", хост.trim())
                            .put("порт", узел.порт)
                            .put("таймаут_секунд", 5)
                            .put("служба", ""),
                    )
                }
            },
        )
    }

    /**
     * Сохраняет профиль и переключает на него приложение. Чужой профиль
     * (правка сервера, который сейчас не показывается) сохраняется молча: опрос
     * активного сервера не прерывается, его настройки не меняются.
     */
    fun сохранить(запускать: Boolean) {
        val настройки = приложение.настройки
        val правки = готово()
        when {
            идСервера != null -> {
                настройки.применить(правки, идСервера)
                if (идСервера == настройки.активныйId) приложение.обновитьСервер()
            }

            новыйСервер -> {
                val созданный = настройки.добавитьСервер(правки, имяСервера)
                приложение.переключитьСервер(созданный)
            }

            else -> {
                // Обычный запуск мастера: правит активный профиль, а если серверов
                // ещё нет — создаёт первый.
                настройки.применить(правки)
                приложение.обновитьСервер()
            }
        }
        приложение.журнал(
            л(
                "Мастер настройки: сервер «{1}», сайтов {2}, портов {3}, служб {4}",
                хост.trim(),
                сайты.size,
                порты.size,
                службы.size,
            ),
        )
        СлужбаМониторинга.запустить(контекст)
        if (запускать) приложение.движок.проверитьСейчас()
        наГотово()
    }

    /** Выход без сохранения: ничего не применяется, настройки остаются прежними. */
    fun выйти() {
        приложение.журнал(л("Мастер настройки закрыт без изменений"))
        наГотово()
    }

    fun пропуститьМастер() {
        приложение.настройки.отметитьМастер()
        приложение.журнал(л("Мастер настройки пропущен — настройка отложена"))
        наГотово()
    }

    /** Один поход на сервер ради автопоиска: соединение открывается и закрывается. */
    suspend fun <Т> наСервере(работа: (LinuxServer) -> Т): Т = withContext(Dispatchers.IO) {
        if (хост.trim().isEmpty() || пользователь.trim().isEmpty()) {
            throw ОшибкаПоиска(л("Сначала заполните адрес и пользователя на шаге «Сервер»"))
        }
        val серверПодключения = LinuxServer(параметры(), приложение::журнал)
        try {
            работа(серверПодключения)
        } finally {
            серверПодключения.закрыть()
        }
    }

    BackHandler {
        if (шаг > 0) шаг -= 1 else выйти()
    }

    Scaffold(containerColor = Палитра.ФОН) { отступы ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(отступы)
                .padding(horizontal = 12.dp),
        ) {
            ШапкаМастера(
                шаг = шаг,
                заголовок = when {
                    новыйСервер -> л("Новый сервер")
                    идСервера != null -> л("Настройка сервера")
                    else -> л("Мастер настройки")
                },
                наВыход = { выйти() },
            )
            Column(modifier = Modifier.weight(1f)) {
                Прокрутка {
                    when (шаг) {
                        0 -> ШагПриветствие(контекст)
                        1 -> ШагСервер(
                            имя = имяСервера,
                            наИмя = { имяСервера = it },
                            включен = включен,
                            наВключен = { включен = it },
                            хост = хост,
                            наХост = { хост = it },
                            порт = порт,
                            наПорт = { порт = it },
                            пользователь = пользователь,
                            наПользователя = { пользователь = it },
                            пароль = пароль,
                            наПароль = { пароль = it },
                            файлКлюча = файлКлюча,
                            наФайлКлюча = { файлКлюча = it },
                            идётПроверка = идётПроверка,
                            ответПроверки = ответПроверки,
                            наПроверку = {
                                область.launch {
                                    идётПроверка = true
                                    ответПроверки = try {
                                        приложение.движок.проверитьПодключение(параметры())
                                    } catch (ошибка: Exception) {
                                        false to (ошибка.message ?: л("Не удалось подключиться"))
                                    }
                                    идётПроверка = false
                                }
                            },
                        )

                        2 -> ШагСайты(
                            сайты = сайты,
                            новыйАдрес = новыйАдрес,
                            наНовыйАдрес = { новыйАдрес = it },
                            идётПоиск = идётПоиск,
                            ошибкаПоиска = ошибкаПоиска,
                            найденыСайты = найденыСайты,
                            есть443 = есть443,
                            искали = искалиСайты,
                            наДобавитьСвой = {
                                val адрес = привестиАдрес(новыйАдрес)
                                if (адрес.isNotEmpty() &&
                                    !добавитьБезДубля(сайты, СайтМастера(имя = адрес, url = адрес), ключ = { ключАдреса(it.url) })
                                ) {
                                    Toast.makeText(контекст, л("Этот сайт уже добавлен"), Toast.LENGTH_SHORT).show()
                                }
                                новыйАдрес = ""
                            },
                            наПоиск = {
                                область.launch {
                                    идётПоиск = true
                                    ошибкаПоиска = null
                                    искалиСайты = true
                                    try {
                                        val найденное = наСервере { соединение ->
                                            val домены = соединение.найтиДомены()
                                            val слушающие = соединение.найтиСлушающиеПорты()
                                            домены to слушающие
                                        }
                                        есть443 = найденное.second.contains(443)
                                        найденыСайты = найденное.first
                                        найденыПорты = if (найденыПорты.isEmpty()) найденное.second else найденыПорты
                                        if (найденное.first.isEmpty()) {
                                            ошибкаПоиска = л("Сайты не найдены: на сервере нет конфигурации nginx/Apache с доменами")
                                        }
                                    } catch (ошибка: Exception) {
                                        ошибкаПоиска = ошибка.message ?: л("Не удалось обратиться к серверу")
                                    }
                                    идётПоиск = false
                                }
                            },
                            наНайденный = { домен ->
                                val адрес = адресКандидата(домен, есть443)
                                if (!добавитьБезДубля(сайты, СайтМастера(имя = домен, url = адрес), ключ = { ключАдреса(it.url) })) {
                                    Toast.makeText(контекст, л("Этот сайт уже добавлен"), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )

                        3 -> ШагПорты(
                            адрес = хост,
                            порты = порты,
                            новыйПорт = новыйПорт,
                            наНовыйПорт = { новыйПорт = it },
                            идётПоиск = идётПоиск,
                            ошибкаПоиска = ошибкаПоиска,
                            найденыПорты = найденыПорты,
                            наДобавитьСвой = {
                                val значение = новыйПорт.trim().toIntOrNull()
                                if (значение != null && значение in 1..65535 &&
                                    !добавитьБезДубля(порты, ПортМастера(имяПорта(значение), значение), ключ = { it.порт.toString() })
                                ) {
                                    Toast.makeText(контекст, л("Этот порт уже добавлен"), Toast.LENGTH_SHORT).show()
                                }
                                новыйПорт = ""
                            },
                            наПоиск = {
                                область.launch {
                                    идётПоиск = true
                                    ошибкаПоиска = null
                                    try {
                                        найденыПорты = наСервере { соединение -> соединение.найтиСлушающиеПорты() }
                                        есть443 = есть443 || найденыПорты.contains(443)
                                        if (найденыПорты.isEmpty()) {
                                            ошибкаПоиска = л("Открытых портов не найдено: сервер не показал список слушающих портов")
                                        }
                                    } catch (ошибка: Exception) {
                                        ошибкаПоиска = ошибка.message ?: л("Не удалось обратиться к серверу")
                                    }
                                    идётПоиск = false
                                }
                            },
                            наНайденный = { значение ->
                                if (!добавитьБезДубля(порты, ПортМастера(имяПорта(значение), значение), ключ = { it.порт.toString() })) {
                                    Toast.makeText(контекст, л("Этот порт уже добавлен"), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )

                        4 -> ШагСлужбы(
                            службы = службы,
                            новаяСлужба = новаяСлужба,
                            наНовуюСлужбу = { новаяСлужба = it },
                            идётПоиск = идётПоиск,
                            ошибкаПоиска = ошибкаПоиска,
                            найденыСлужбы = найденыСлужбы,
                            наДобавитьСвой = {
                                val имя = новаяСлужба.trim()
                                if (имя.isNotEmpty() && !добавитьСлужбу(службы, имя)) {
                                    Toast.makeText(контекст, л("Эта служба уже добавлена"), Toast.LENGTH_SHORT).show()
                                }
                                новаяСлужба = ""
                            },
                            наПоиск = {
                                область.launch {
                                    идётПоиск = true
                                    ошибкаПоиска = null
                                    try {
                                        найденыСлужбы = безШумаСлужб(наСервере { соединение -> соединение.найтиСлужбы() })
                                            .sortedBy { пара ->
                                                val позиция = ЧАСТЫЕ_СЛУЖБЫ.indexOf(пара.first.removeSuffix(".service"))
                                                if (позиция < 0) 99 else позиция
                                            }
                                        if (найденыСлужбы.isEmpty()) {
                                            ошибкаПоиска = л("Работающие службы не найдены: проверьте, что на сервере есть systemd")
                                        }
                                    } catch (ошибка: Exception) {
                                        ошибкаПоиска = ошибка.message ?: л("Не удалось обратиться к серверу")
                                    }
                                    идётПоиск = false
                                }
                            },
                            наНайденный = { имя ->
                                if (!добавитьСлужбу(службы, имя)) {
                                    Toast.makeText(контекст, л("Эта служба уже добавлена"), Toast.LENGTH_SHORT).show()
                                }
                            },
                        )

                        else -> ШагГотово(
                            имя = имяСервера.trim(),
                            адрес = л("{1}:{2}", хост.trim(), порт.trim().toIntOrNull() ?: 22),
                            пользователь = пользователь.trim(),
                            сайтов = сайты.size,
                            портов = порты.size,
                            служб = службы.size,
                            замечания = приложение.настройки.проверить(готово()),
                        )
                    }
                }
            }
            НизМастера(
                шаг = шаг,
                наНазад = { if (шаг > 0) шаг-- },
                наДалее = { if (шаг < ШАГИ.lastIndex) шаг++ },
                наПропустить = {
                    when {
                        // Чужой профиль «пропуском» не отмечается: просто выходим.
                        шаг == 0 && (новыйСервер || идСервера != null) -> выйти()
                        шаг == 0 -> пропуститьМастер()
                        шаг < ШАГИ.lastIndex -> шаг++
                    }
                },
                наСохранить = { сохранить(запускать = true) },
                наПозже = { сохранить(запускать = false) },
            )
        }
    }
}

/** Ошибка автопоиска: отдельный тип, чтобы текст можно было показать пользователю. */
private class ОшибкаПоиска(сообщение: String) : Exception(сообщение)

@Composable
private fun ШапкаМастера(шаг: Int, заголовок: String, наВыход: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Текст(заголовок, color = Палитра.ТЕКСТ, style = MaterialTheme.typography.titleMedium)
                Текст(
                    текст = л("Шаг {1} из {2}: {3}", шаг + 1, ШАГИ.size, ШАГИ[шаг]),
                    color = Палитра.ТУСКЛЫЙ,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            TextButton(onClick = наВыход) { Текст(л("Выйти")) }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ШАГИ.indices.forEach { номер ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (номер <= шаг) Палитра.АКЦЕНТ else Палитра.ГРАНИЦА),
                )
            }
        }
    }
}

@Composable
private fun НизМастера(
    шаг: Int,
    наНазад: () -> Unit,
    наДалее: () -> Unit,
    наПропустить: () -> Unit,
    наСохранить: () -> Unit,
    наПозже: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (шаг > 0) TextButton(onClick = наНазад) { Текст(л("Назад")) }
        Spacer(Modifier.weight(1f))
        when (шаг) {
            0 -> {
                TextButton(onClick = наПропустить) { Текст(л("Пропустить")) }
                КнопкаГлавная(л("Начать"), наДалее)
            }

            ШАГИ.lastIndex -> {
                TextButton(onClick = наПозже) { Текст(л("Настроить позже")) }
                КнопкаГлавная(л("Начать мониторинг"), наСохранить)
            }

            else -> {
                TextButton(onClick = наПропустить) { Текст(л("Пропустить шаг")) }
                КнопкаГлавная(л("Далее"), наДалее)
            }
        }
    }
}

@Composable
private fun ШагПриветствие(контекст: Context) {
    Карточка(л("Добро пожаловать")) {
        Текст(
            текст = л("Приложение следит за вашим Linux-сервером: процессор, оперативная память, диски и сеть, доступность сайтов и портов, состояние служб systemd и защита fail2ban. Все настройки — только ваши: приложение ничего не знает о чужих серверах."),
            color = Палитра.ТЕКСТ,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        СтрокаЗначения(л("Разработчик"), РАЗРАБОТЧИК, моно = false)
        СтрокаЗначения(л("Сайт"), "astuslab.com.ua", цвет = Палитра.АКЦЕНТ)
        РядКнопок(listOf(л("Открыть сайт разработчика") to { открытьСсылку(контекст, САЙТ_РАЗРАБОТЧИКА) }))
    }

    Карточка(л("Что понадобится")) {
        СтрокаЗначения("1", л("Адрес сервера и порт SSH"))
        СтрокаЗначения("2", л("Логин и пароль (или файл ключа)"))
        СтрокаЗначения("3", л("Сайты, порты и службы"))
        Подсказка(
            л("Список сайтов, портов и служб можно не вводить руками: на шаге каждого списка есть кнопка поиска — приложение прочитает конфигурацию nginx/Apache, слушающие порты и работающие службы прямо на сервере."),
        )
    }
}

@Composable
private fun ШагСервер(
    имя: String,
    наИмя: (String) -> Unit,
    включен: Boolean,
    наВключен: (Boolean) -> Unit,
    хост: String,
    наХост: (String) -> Unit,
    порт: String,
    наПорт: (String) -> Unit,
    пользователь: String,
    наПользователя: (String) -> Unit,
    пароль: String,
    наПароль: (String) -> Unit,
    файлКлюча: String,
    наФайлКлюча: (String) -> Unit,
    идётПроверка: Boolean,
    ответПроверки: Pair<Boolean, String>?,
    наПроверку: () -> Unit,
) {
    Карточка(л("Подключение к серверу по SSH")) {
        ПереключательМастера(л("Мониторить сервер"), включен, наВключен)
        ПолеВводаМастера(л("Имя сервера"), имя, наИмя)
        ПолеВводаМастера(л("Адрес или домен"), хост, наХост)
        ПолеВводаМастера(л("Порт SSH"), порт, наПорт, числовое = true)
        ПолеВводаМастера(л("Пользователь"), пользователь, наПользователя)
        ПолеВводаМастера(л("Пароль"), пароль, наПароль, секрет = true)
        ПолеВводаМастера(л("Файл ключа (необязательно)"), файлКлюча, наФайлКлюча)
        РядКнопок(listOf((if (идётПроверка) л("Проверяю…") else л("Проверить подключение")) to наПроверку))
        ответПроверки?.let { ответ ->
            Текст(
                текст = ответ.second,
                color = if (ответ.first) Палитра.ЗЕЛЁНЫЙ else Палитра.КРАСНЫЙ,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Подсказка(
            л("Проверка идёт мимо рабочих настроек: приложение откроет отдельное соединение и не сохранит ничего лишнего. Логин должен уметь читать /proc и запускать systemctl."),
        )
        Подсказка(
            л("Имя сервера нужно, когда их несколько: так проще выбрать нужный в списке. Если оставить пустым, будет показан адрес сервера."),
        )
        Подсказка(
            л("Пароль в файл config.json не записывается: он лежит в защищённом хранилище Android (Keystore) и в настройках видна только отметка о том, что пароль сохранён."),
        )
    }
}

@Composable
private fun ШагСайты(
    сайты: MutableList<СайтМастера>,
    новыйАдрес: String,
    наНовыйАдрес: (String) -> Unit,
    идётПоиск: Boolean,
    ошибкаПоиска: String?,
    найденыСайты: List<String>,
    есть443: Boolean,
    искали: Boolean,
    наДобавитьСвой: () -> Unit,
    наПоиск: () -> Unit,
    наНайденный: (String) -> Unit,
) {
    Карточка(л("Сайты для проверки"), подзаголовок = л("Добавлено: {1}", сайты.size)) {
        if (сайты.isEmpty()) {
            Текст(л("Пока пусто"), color = Палитра.ТУСКЛЫЙ, style = MaterialTheme.typography.bodySmall)
        }
        сайты.toList().forEach { сайт ->
            СтрокаСписка(подпись = л("{1} — {2}", сайт.имя, сайт.url)) { сайты.remove(сайт) }
        }
        ПолеВводаМастера(л("Адрес сайта, например https://мой-сайт.ру"), новыйАдрес, наНовыйАдрес)
        РядКнопок(listOf(л("Добавить сайт") to наДобавитьСвой))
        Подсказка(л("Проверяются код ответа и срок действия сертификата. Ожидаются коды 200, 301 и 302."))
    }

    Карточка(л("Поиск сайтов на сервере")) {
        РядКнопок(listOf((if (идётПоиск) л("Ищу…") else л("Найти сайты на сервере")) to наПоиск))
        Подсказка(л("Приложение прочитает server_name в nginx и ServerName в Apache, а также проверит, есть ли на сервере HTTPS."))
        if (найденыСайты.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            найденыСайты.forEach { домен ->
                val адрес = адресКандидата(домен, есть443)
                Кандидат(
                    подпись = домен,
                    подсказка = адрес,
                    добавлен = естьЭлемент(сайты, ключАдреса(адрес)) { ключАдреса(it.url) },
                    наДобавить = { наНайденный(домен) },
                )
            }
        }
        ошибкаПоиска?.let { текст ->
            Текст(текст, color = Палитра.ЖЁЛТЫЙ, style = MaterialTheme.typography.bodySmall)
        }
        if (искали && найденыСайты.isEmpty()) {
            Подсказка(л("Ничего не нашлось — добавьте адреса вручную выше. Так бывает, если сайты обслуживает не nginx и не Apache."))
        }
    }
}

@Composable
private fun ШагПорты(
    адрес: String,
    порты: MutableList<ПортМастера>,
    новыйПорт: String,
    наНовыйПорт: (String) -> Unit,
    идётПоиск: Boolean,
    ошибкаПоиска: String?,
    найденыПорты: List<Int>,
    наДобавитьСвой: () -> Unit,
    наПоиск: () -> Unit,
    наНайденный: (Int) -> Unit,
) {
    Карточка(л("Открытые порты"), подзаголовок = л("Добавлено: {1}", порты.size)) {
        if (порты.isEmpty()) {
            Текст(л("Пока пусто"), color = Палитра.ТУСКЛЫЙ, style = MaterialTheme.typography.bodySmall)
        }
        порты.toList().forEach { узел ->
            СтрокаСписка(подпись = "${узел.имя} · ${адрес.ifBlank { л("адрес сервера") }}:${узел.порт}") { порты.remove(узел) }
        }
        ПолеВводаМастера(л("Номер порта, например 443"), новыйПорт, наНовыйПорт, числовое = true)
        РядКнопок(listOf(л("Добавить порт") to наДобавитьСвой))
        Подсказка(л("Проверка порта — это обычное TCP-подключение к адресу сервера по указанному номеру."))
    }

    Карточка(л("Поиск слушающих портов")) {
        РядКнопок(listOf((if (идётПоиск) л("Ищу…") else л("Найти порты на сервере")) to наПоиск))
        if (найденыПорты.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            найденыПорты.forEach { значение ->
                Кандидат(
                    подпись = имяПорта(значение),
                    подсказка = л("порт {1}", значение),
                    добавлен = естьЭлемент(порты, значение.toString()) { it.порт.toString() },
                    наДобавить = { наНайденный(значение) },
                )
            }
        }
        ошибкаПоиска?.let { текст ->
            Текст(текст, color = Палитра.ЖЁЛТЫЙ, style = MaterialTheme.typography.bodySmall)
        }
        Подсказка(л("Показываются только порты, доступные извне (0.0.0.0 или ::). Порты с привязкой к localhost пропускаются."))
    }
}

@Composable
private fun ШагСлужбы(
    службы: MutableList<String>,
    новаяСлужба: String,
    наНовуюСлужбу: (String) -> Unit,
    идётПоиск: Boolean,
    ошибкаПоиска: String?,
    найденыСлужбы: List<Pair<String, String>>,
    наДобавитьСвой: () -> Unit,
    наПоиск: () -> Unit,
    наНайденный: (String) -> Unit,
) {
    Карточка(л("Службы systemd"), подзаголовок = л("Добавлено: {1}", службы.size)) {
        if (службы.isEmpty()) {
            Текст(л("Пока пусто"), color = Палитра.ТУСКЛЫЙ, style = MaterialTheme.typography.bodySmall)
        }
        службы.toList().forEach { имя ->
            СтрокаСписка(подпись = имя) { службы.remove(имя) }
        }
        ПолеВводаМастера(л("Имя службы, например nginx.service"), новаяСлужба, наНовуюСлужбу)
        РядКнопок(listOf(л("Добавить службу") to наДобавитьСвой))
        Подсказка(л("Приложение показывает состояние службы и умеет её перезапустить. Для перезапуска нужны права sudo."))
    }

    Карточка(л("Поиск работающих служб")) {
        РядКнопок(listOf((if (идётПоиск) л("Ищу…") else л("Показать службы на сервере")) to наПоиск))
        if (найденыСлужбы.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            найденыСлужбы.forEach { пара ->
                Кандидат(
                    подпись = пара.first,
                    подсказка = пара.second,
                    // Сравнение через нормализацию: в сохранённых настройках старые
                    // записи могли остаться без суффикса ".service" (например "ssh"
                    // вместо "ssh.service"), а найденные на сервере службы всегда
                    // приходят с суффиксом — иначе уже добавленная служба ошибочно
                    // показывалась бы кнопкой «Добавить» вместо «добавлено».
                    добавлен = естьСлужба(службы, пара.first),
                    наДобавить = { наНайденный(пара.first) },
                )
            }
        }
        ошибкаПоиска?.let { текст ->
            Текст(текст, color = Палитра.ЖЁЛТЫЙ, style = MaterialTheme.typography.bodySmall)
        }
        if (найденыСлужбы.isEmpty()) {
            Подсказка(л("Показываются только запущенные службы. Служебные юниты systemd скрыты, чтобы список был короче."))
        }
    }
}

@Composable
private fun ШагГотово(
    имя: String,
    адрес: String,
    пользователь: String,
    сайтов: Int,
    портов: Int,
    служб: Int,
    замечания: List<String>,
) {
    Карточка(л("Проверьте настройки")) {
        if (имя.isNotBlank()) СтрокаЗначения(л("Имя сервера"), имя)
        СтрокаЗначения(л("Сервер"), адрес.ifBlank { л("не указан") })
        СтрокаЗначения(л("Пользователь"), пользователь.ifBlank { л("не указан") })
        СтрокаЗначения(л("Сайтов"), сайтов.toString())
        СтрокаЗначения(л("Портов"), портов.toString())
        СтрокаЗначения(л("Служб"), служб.toString())
    }

    if (замечания.isNotEmpty()) {
        Карточка(л("На что обратить внимание")) {
            замечания.forEach { пункт ->
                Текст(л("• {1}", пункт), color = Палитра.ЖЁЛТЫЙ, style = MaterialTheme.typography.bodySmall)
            }
            Подсказка(л("Настройки можно сохранить и так — их всегда можно поправить на вкладке «Настройки»."))
        }
    }

    Карточка(л("Что дальше")) {
        Текст(
            текст = л("«Начать мониторинг» сохранит настройки и сразу опросит сервер. «Настроить позже» сохранит то, что вы уже ввели, но не станет опрашивать сервер сейчас."),
            color = Палитра.ТУСКЛЫЙ,
            style = MaterialTheme.typography.bodySmall,
        )
        Подсказка(л("Мастер можно открыть снова: вкладка «Настройки» → «Мастер настройки»."))
    }
}

@Composable
private fun КнопкаГлавная(подпись: String, наНажатие: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Палитра.АКЦЕНТ)
            .clickable { наНажатие() }
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Текст(подпись, color = Палитра.ФОН, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ПолеВводаМастера(
    подпись: String,
    значение: String,
    наИзменение: (String) -> Unit,
    числовое: Boolean = false,
    секрет: Boolean = false,
) {
    OutlinedTextField(
        value = значение,
        onValueChange = наИзменение,
        label = { Текст(подпись) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (числовое) KeyboardType.Number else KeyboardType.Text),
        visualTransformation = if (секрет) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    )
}

@Composable
private fun ПереключательМастера(подпись: String, включено: Boolean, наИзменение: (Boolean) -> Unit) {
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

@Composable
private fun СтрокаСписка(подпись: String, наУбрать: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Текст(
            текст = подпись,
            color = Палитра.ТЕКСТ,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = наУбрать) { Текст(л("Убрать"), color = Палитра.КРАСНЫЙ) }
    }
}

@Composable
private fun Кандидат(подпись: String, подсказка: String, добавлен: Boolean, наДобавить: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Текст(подпись, color = Палитра.ТЕКСТ, style = MaterialTheme.typography.bodySmall)
            if (подсказка.isNotBlank()) {
                Текст(
                    текст = подсказка,
                    color = Палитра.ТУСКЛЫЙ,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (добавлен) {
            Текст(л("добавлено"), color = Палитра.ЗЕЛЁНЫЙ, style = MaterialTheme.typography.labelSmall)
        } else {
            TextButton(onClick = наДобавить) { Текст(л("Добавить")) }
        }
    }
}

/** Подпись порта: «Порт 443 (HTTPS)» или просто «Порт 1234». */
internal fun имяПорта(значение: Int): String {
    val известное = ИМЕНА_ПОРТОВ[значение]
    return if (известное == null) л("Порт {1}", значение) else л("Порт {1} ({2})", значение, известное)
}

/** Добавляет схему к адресу, если пользователь её не написал. */
internal fun привестиАдрес(введённое: String): String {
    val адрес = введённое.trim().trimEnd('/')
    if (адрес.isEmpty()) return ""
    if (адрес.contains("://")) return адрес
    val локальный = адрес.startsWith("192.168.") || адрес.startsWith("10.") ||
        адрес.startsWith("172.16.") || адрес.startsWith("127.") || адрес.contains(":8000")
    return if (локальный) л("http://{1}", адрес) else л("https://{1}", адрес)
}

/**
 * Адрес сайта для найденного на сервере имени: HTTPS только для домена с сертификатом —
 * у голого IP сертификат выписан на домен, и проверка SSL всегда падала бы.
 */
internal fun адресКандидата(имя: String, есть443: Boolean): String {
    val похожеНаIp = Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(имя.trim())
    return if (есть443 && !похожеНаIp) л("https://{1}", имя) else л("http://{1}", имя)
}
