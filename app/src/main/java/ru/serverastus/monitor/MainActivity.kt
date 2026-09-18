package ru.serverastus.monitor

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import ru.serverastus.monitor.данные.Настройки
import ru.serverastus.monitor.данные.Снимок
import ru.serverastus.monitor.данные.л
import ru.serverastus.monitor.данные.форматПроцент
import ru.serverastus.monitor.мониторинг.СлужбаМониторинга
import ru.serverastus.monitor.экран.Текст
import ru.serverastus.monitor.экран.ЭкранБезопасность
import ru.serverastus.monitor.экран.ЭкранЖурнала
import ru.serverastus.monitor.экран.ЭкранНастроек
import ru.serverastus.monitor.экран.ЭкранОбслуживания
import ru.serverastus.monitor.экран.ЭкранПроверки
import ru.serverastus.monitor.экран.ЭкранСервер
import ru.serverastus.monitor.экран.ЭкранСерверов
import ru.serverastus.monitor.экран.ЭкранСети
import ru.serverastus.monitor.экран.ЭкранСлужбы
import ru.serverastus.monitor.экран.Крутилка
import ru.serverastus.monitor.экран.Лампа
import ru.serverastus.monitor.экран.Палитра
import ru.serverastus.monitor.экран.ПорогиИнтерфейса
import ru.serverastus.monitor.экран.ТемаПриложения
import ru.serverastus.monitor.экран.ЭкранМастера

private val ВКЛАДКИ = listOf(
    л("Сервер"), л("Проверки"), л("Сеть"), л("Обслуживание"), л("Службы"), л("Безопасность"), л("Журнал"), л("Настройки"),
)

/** Единственная активность приложения: шапка со сводкой и семь вкладок. */
class MainActivity : ComponentActivity() {

    override fun onCreate(состояние: Bundle?) {
        super.onCreate(состояние)
        val приложение = application as Монитор

        setContent {
            ТемаПриложения {
                ГлавныйЭкран(приложение)
            }
        }

        запроситьРазрешениеНаУведомления()
        if (!приложение.движок.снимок.value.запущено) СлужбаМониторинга.запустить(this)
    }

    override fun onStart() {
        super.onStart()
        val приложение = application as Монитор
        приложение.журнал(л("Окно приложения открыто"))
        if (!приложение.движок.снимок.value.запущено) СлужбаМониторинга.запустить(this)
    }

    private fun запроситьРазрешениеНаУведомления() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val разрешено = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!разрешено) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }
}

/** Какой раздел показывает активность: рабочие вкладки, мастер или список серверов. */
private enum class РазделПриложения { Работа, Мастер, НовыйСервер, ПравкаСервера, Серверы }

@Composable
private fun ГлавныйЭкран(приложение: Монитор) {
    // Первый запуск (или сброс настроек) — сначала мастер: у пользователя
    // ещё нет ни сервера, ни сайтов, ни служб, показывать пустые вкладки нечего.
    var раздел by remember {
        mutableStateOf(
            if (приложение.настройки.мастерПройден) РазделПриложения.Работа else РазделПриложения.Мастер,
        )
    }
    // Профиль, который правит мастер: пусто — обычный запуск мастера.
    var правимый by remember { mutableStateOf("") }

    when (раздел) {
        РазделПриложения.Мастер -> ЭкранМастера(
            приложение,
            наГотово = { раздел = РазделПриложения.Работа },
        )

        // Новый сервер настраивается тем же мастером, но в отдельном профиле:
        // настройки уже существующих серверов при этом не меняются.
        РазделПриложения.НовыйСервер -> ЭкранМастера(
            приложение,
            наГотово = { раздел = РазделПриложения.Работа },
            новыйСервер = true,
        )

        РазделПриложения.ПравкаСервера -> ЭкранМастера(
            приложение,
            наГотово = { раздел = РазделПриложения.Серверы },
            идСервера = правимый,
        )

        РазделПриложения.Серверы -> ЭкранСерверов(
            приложение = приложение,
            наНазад = { раздел = РазделПриложения.Работа },
            наОткрыть = { ид ->
                приложение.переключитьСервер(ид)
                раздел = РазделПриложения.Работа
            },
            наИзменить = { ид ->
                правимый = ид
                раздел = РазделПриложения.ПравкаСервера
            },
            наДобавить = { раздел = РазделПриложения.НовыйСервер },
        )

        РазделПриложения.Работа -> РабочийЭкран(
            приложение = приложение,
            наОткрытьМастер = { раздел = РазделПриложения.Мастер },
            наОткрытьСерверы = { раздел = РазделПриложения.Серверы },
            наДобавитьСервер = { раздел = РазделПриложения.НовыйСервер },
        )
    }
}

@Composable
private fun РабочийЭкран(
    приложение: Монитор,
    наОткрытьМастер: () -> Unit,
    наОткрытьСерверы: () -> Unit,
    наДобавитьСервер: () -> Unit,
) {
    val получен by приложение.движок.снимок.collectAsState()
    val настройки = приложение.настройки
    val идАктивный = настройки.активныйId
    // Запоздавший опрос прежнего сервера не должен показаться на экранах нового:
    // снимок помечен тем сервером, чьи данные в нём, и чужой мы просто пропускаем.
    val снимок = if (получен.серверId == идАктивный) {
        получен
    } else {
        Снимок(
            запущено = получен.запущено,
            серверId = идАктивный,
            имяПрофиля = настройки.имяПрофиля(),
        )
    }
    val подсказки = remember { SnackbarHostState() }
    // Пороги — часть профиля: при переходе на другой сервер их нужно перечитать.
    val пороги = remember(идАктивный) { настройки.пороги() }

    var вкладка by remember { mutableStateOf(ВКЛАДКИ.first()) }
    var отсечкаЖурнала by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        приложение.движок.сообщения.collect { сообщение ->
            подсказки.showSnackbar(сообщение)
        }
    }

    LaunchedEffect(вкладка) {
        if (вкладка == л("Журнал")) приложение.движок.обновитьСобытия()
    }

    // У другого сервера своя история — прежнее ограничение окна журнала не годится.
    LaunchedEffect(идАктивный) { отсечкаЖурнала = 0L }

    Scaffold(
        containerColor = Палитра.ФОН,
        snackbarHost = { SnackbarHost(подсказки) },
    ) { отступы ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(отступы)
                .padding(horizontal = 12.dp),
        ) {
            Шапка(
                приложение = приложение,
                снимок = снимок,
                пороги = ПорогиИнтерфейса(пороги.цп, пороги.озу, пороги.диск),
                наОткрытьМастер = наОткрытьМастер,
                наОткрытьСерверы = наОткрытьСерверы,
                наДобавитьСервер = наДобавитьСервер,
            )
            ПолосаВкладок(вкладка) { вкладка = it }
            Column(modifier = Modifier.fillMaxSize()) {
                when (вкладка) {
                    л("Сервер") -> ЭкранСервер(
                        снимок = снимок,
                        порогиСостояние = ПорогиИнтерфейса(пороги.цп, пороги.озу, пороги.диск),
                    )

                    л("Проверки") -> ЭкранПроверки(снимок)

                    л("Сеть") -> ЭкранСети(снимок, приложение.движок)

                    л("Обслуживание") -> ЭкранОбслуживания(снимок, приложение.движок)

                    л("Службы") -> ЭкранСлужбы(снимок, приложение.движок)

                    л("Безопасность") -> ЭкранБезопасность(снимок, приложение.движок)

                    л("Журнал") -> ЭкранЖурнала(
                        снимок = снимок,
                        движок = приложение.движок,
                        отсечка = отсечкаЖурнала,
                        наОтсечку = { отсечкаЖурнала = it },
                    )

                    else -> ЭкранНастроек(приложение, снимок, наОткрытьМастер, наОткрытьСерверы)
                }
            }
        }
    }
}

/** Цвет точки состояния сервера в списках. */
private fun цветСостояния(состояние: String): Color = when (состояние) {
    "онлайн" -> Палитра.ЗЕЛЁНЫЙ
    "недоступен" -> Палитра.КРАСНЫЙ
    else -> Палитра.ТУСКЛЫЙ
}

/** Адрес сервера для списков: «хост:порт». */
private fun адресПрофиля(настройки: Настройки, узел: JSONObject): String {
    val хост = настройки.текст(узел, "хост")
    if (хост.isEmpty()) return л("адрес не задан")
    return хост + ":" + настройки.целое(узел, "порт", 22)
}

@Composable
private fun Шапка(
    приложение: Монитор,
    снимок: Снимок,
    пороги: ПорогиИнтерфейса,
    наОткрытьМастер: () -> Unit,
    наОткрытьСерверы: () -> Unit,
    наДобавитьСервер: () -> Unit,
) {
    val настройки = приложение.настройки
    val списокСерверов = настройки.серверы
    val активный = настройки.активныйId
    var списокОткрыт by remember { mutableStateOf(false) }

    val цветСвязи = when (снимок.связь) {
        true -> Палитра.ЗЕЛЁНЫЙ
        false -> Палитра.КРАСНЫЙ
        null -> Палитра.ТУСКЛЫЙ
    }
    val метрики = снимок.метрики

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(Палитра.КАРТОЧКА_ВЫШЕ, Палитра.КАРТОЧКА)))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.clickable { списокОткрыт = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Лампа(цвет = цветСвязи, размер = 12.dp)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Текст(
                                текст = снимок.имяПрофиля.ifBlank { снимок.имяСервера }
                                    .ifBlank { настройки.текст(настройки.узелСервера(), "хост") }
                                    .ifBlank { л("Сервер не настроен") },
                                color = Палитра.ТЕКСТ,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Текст(
                                текст = "▼",
                                color = Палитра.АКЦЕНТ,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                        Текст(
                            текст = buildString {
                                append(снимок.текстСвязи)
                                снимок.задержкаМс?.let { append(л(" · {1} мс", it)) }
                                if (снимок.пауза) append(л(" · пауза"))
                            },
                            color = Палитра.ТУСКЛЫЙ,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                СписокСерверов(
                    открыт = списокОткрыт,
                    закрыть = { списокОткрыт = false },
                    настройки = настройки,
                    серверы = списокСерверов,
                    активный = активный,
                    наВыбрать = { ид ->
                        списокОткрыт = false
                        if (ид != активный) приложение.переключитьСервер(ид)
                    },
                    наОткрытьСерверы = {
                        списокОткрыт = false
                        наОткрытьСерверы()
                    },
                    наДобавитьСервер = {
                        списокОткрыт = false
                        наДобавитьСервер()
                    },
                )
            }
            if (снимок.опросИдёт) Крутилка(цвет = Палитра.АКЦЕНТ, размер = 16.dp)
        }

        if (метрики != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                СводкаШапки(л("ЦП"), форматПроцент(метрики.цп), Палитра.нагрузки(метрики.цп, пороги.цп))
                СводкаШапки(л("ОЗУ"), форматПроцент(метрики.озуПроцент), Палитра.нагрузки(метрики.озуПроцент, пороги.озу))
                СводкаШапки(
                    л("Диск"),
                    форматПроцент(метрики.дискПроцент),
                    Палитра.нагрузки(метрики.дискПроцент, пороги.диск),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!настройки.настроен()) {
                TextButton(onClick = наОткрытьМастер) { Текст(л("Мастер настройки")) }
            }
            TextButton(onClick = { приложение.движок.проверитьСейчас() }) { Текст(л("Обновить")) }
            TextButton(
                onClick = {
                    if (!снимок.запущено) {
                        СлужбаМониторинга.запустить(приложение)
                    } else {
                        СлужбаМониторинга.паузаИлиПродолжить(приложение)
                    }
                },
            ) {
                Текст(
                    when {
                        !снимок.запущено -> л("Запустить")
                        снимок.пауза -> л("Продолжить")
                        else -> л("Пауза")
                    },
                )
            }
            TextButton(onClick = { СлужбаМониторинга.остановить(приложение) }) { Текст(л("Остановить")) }
        }
    }
}

/** Компактный список серверов под шапкой: переключение, управление, добавление. */
@Composable
private fun СписокСерверов(
    открыт: Boolean,
    закрыть: () -> Unit,
    настройки: Настройки,
    серверы: List<JSONObject>,
    активный: String,
    наВыбрать: (String) -> Unit,
    наОткрытьСерверы: () -> Unit,
    наДобавитьСервер: () -> Unit,
) {
    DropdownMenu(
        expanded = открыт,
        onDismissRequest = закрыть,
        containerColor = Палитра.КАРТОЧКА_ВЫШЕ,
    ) {
        if (серверы.isEmpty()) {
            DropdownMenuItem(
                text = { Текст(л("Серверов пока нет"), color = Палитра.ТУСКЛЫЙ) },
                onClick = закрыть,
            )
        }
        серверы.forEach { узел ->
            val ид = настройки.текст(узел, "id")
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Лампа(цвет = цветСостояния(настройки.состояние(ид)), размер = 8.dp)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Текст(
                                текст = if (ид == активный) {
                                    л("{1} · выбран", настройки.подпись(ид))
                                } else {
                                    настройки.подпись(ид)
                                },
                                color = if (ид == активный) Палитра.АКЦЕНТ else Палитра.ТЕКСТ,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Текст(
                                текст = адресПрофиля(настройки, узел),
                                color = Палитра.ТУСКЛЫЙ,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                },
                onClick = { наВыбрать(ид) },
            )
        }
        DropdownMenuItem(
            text = { Текст(л("Серверы"), color = Палитра.АКЦЕНТ) },
            onClick = наОткрытьСерверы,
        )
        DropdownMenuItem(
            text = { Текст(л("+ Добавить сервер"), color = Палитра.АКЦЕНТ) },
            onClick = наДобавитьСервер,
        )
    }
}

@Composable
private fun СводкаШапки(подпись: String, значение: String, цвет: Color) {
    Column(modifier = Modifier.width(84.dp)) {
        Текст(подпись, color = Палитра.ТУСКЛЫЙ, style = MaterialTheme.typography.labelSmall)
        Текст(значение, color = цвет, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ПолосаВкладок(выбрана: String, наВыбор: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ВКЛАДКИ.forEach { имя ->
            val активна = имя == выбрана
            Текст(
                текст = имя,
                color = if (активна) Палитра.ФОН else Палитра.ТУСКЛЫЙ,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (активна) Палитра.АКЦЕНТ else Палитра.КАРТОЧКА)
                    .clickable { наВыбор(имя) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}
