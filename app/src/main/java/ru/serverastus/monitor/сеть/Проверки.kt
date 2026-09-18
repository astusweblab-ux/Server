package ru.serverastus.monitor.сеть

import org.json.JSONObject
import ru.serverastus.monitor.данные.РезультатПроверки
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.IDN
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import ru.serverastus.monitor.данные.л

/**
 * Проверки доступности сайтов и сетевых портов — повторяют `checks.py`
 * настольной версии (те же формулировки, коды и правила нормализации адреса).
 */

// Заголовки HTTP по стандарту кодируются в latin-1, поэтому только латиница.
const val ПОЛЬЗОВАТЕЛЬ_АГЕНТ = "ServerMonitor/1.0 (server-and-site-monitoring)"

// Расшифровка частых кодов ответа, чтобы в отчёте было понятно, что случилось.
private val ПОДСКАЗКИ_КОДОВ = mapOf(
    400 to л("неверный запрос"),
    401 to л("требуется авторизация"),
    403 to л("доступ запрещён"),
    404 to л("страница не найдена"),
    405 to л("метод не разрешён"),
    408 to л("сервер не дождался запроса"),
    429 to л("слишком много запросов, включилось ограничение"),
    500 to л("внутренняя ошибка сервера (ошибка в коде сайта)"),
    501 to л("сервер не поддерживает запрошенный метод"),
    502 to л("шлюз получил неверный ответ от сервера приложения"),
    503 to л("служба недоступна: сервер перегружен или на обслуживании"),
    504 to л("шлюз не дождался ответа от сервера приложения"),
    520 to л("Cloudflare: сервер вернул неизвестную ошибку"),
    521 to л("Cloudflare: веб-сервер не отвечает"),
    522 to л("Cloudflare: соединение с сервером не установлено"),
    523 to л("Cloudflare: сервер-источник недоступен"),
    524 to л("Cloudflare: превышено время ожидания от сервера-источника"),
    525 to л("Cloudflare: не удалось выполнить SSL-рукопожатие с сервером"),
    526 to л("Cloudflare: недействительный SSL-сертификат сервера"),
    527 to л("Cloudflare: ошибка соединения Railgun"),
    530 to л("Cloudflare: ошибка DNS или неверный заголовок запроса"),
)

// Служебные «причины» в ответе сервера, которые не стоит показывать пользователю.
private val ПУСТЫЕ_ПРИЧИНЫ = setOf("", "none", "<none>", "unknown", "null", "-")

private const val БЕЗОПАСНЫЕ_ПУТЬ = "/%:@+$,;~()!*'=&"
private const val БЕЗОПАСНЫЕ_ЗАПРОС = "=&%:@+$,;~()!*'/?.^|"
private const val ВСЕГДА_БЕЗОПАСНЫЕ = "_.-~"
private const val ПРЕДЕЛ_ОТВЕТА = 65536

/** Пояснение к коду ответа, например «(страница не найдена)». */
fun описаниеКода(код: Int?): String {
    val подсказка = код?.let { ПОДСКАЗКИ_КОДОВ[it] }
    return if (подсказка != null) л(" ({1})", подсказка) else ""
}

/** Очищает причину из ответа сервера от служебных значений. */
fun чистаяПричина(причина: String?): String {
    val текст = (причина ?: "").trim()
    return if (текст.lowercase() in ПУСТЫЕ_ПРИЧИНЫ) "" else текст
}

/** Убирает символы, которые нельзя передать в заголовке HTTP. */
private fun безопасныйЗаголовок(значение: String): String =
    значение.map { if (it.code > 255) '?' else it }.joinToString("")

/** Число в коротком виде — как `{:g}` в Python: 10.0 → «10», 2.5 → «2.5». */
private fun краткоеЧисло(значение: Double): String {
    if (значение.isNaN() || значение.isInfinite()) return значение.toString()
    if (значение == Math.floor(значение) && kotlin.math.abs(значение) < 1e15) {
        return значение.toLong().toString()
    }
    return String.format(Locale.ROOT, "%.3f", значение).trimEnd('0').trimEnd('.')
}

/** Кодирует строку для адреса: буквы, цифры и перечисленные символы остаются. */
private fun проценты(текст: String, безопасные: String): String {
    val итог = StringBuilder()
    for (байт in текст.toByteArray(Charsets.UTF_8)) {
        val код = байт.toInt() and 0xFF
        val символ = код.toChar()
        val букваИлиЦифра = код < 128 && (символ.isLetterOrDigit())
        if (букваИлиЦифра || ВСЕГДА_БЕЗОПАСНЫЕ.contains(символ) || безопасные.contains(символ)) {
            итог.append(символ)
        } else {
            итог.append('%').append(String.format(Locale.ROOT, "%02X", код))
        }
    }
    return итог.toString()
}

private data class РазобранныйАдрес(
    val схема: String,
    val хост: String,
    val порт: Int,
    val путь: String,
    val запрос: String,
    val фрагмент: String,
)

/** Разбирает адрес вручную: русские буквы в домене не должны ломать разбор. */
private fun разобратьАдрес(адрес: String): РазобранныйАдрес? {
    val начало = адрес.indexOf("://")
    if (начало < 0) return null
    val схема = адрес.substring(0, начало).lowercase()
    var остаток = адрес.substring(начало + 3)
    if (остаток.isEmpty()) return null

    var фрагмент = ""
    val решётка = остаток.indexOf('#')
    if (решётка >= 0) {
        фрагмент = остаток.substring(решётка + 1)
        остаток = остаток.substring(0, решётка)
    }
    var запрос = ""
    val вопрос = остаток.indexOf('?')
    if (вопрос >= 0) {
        запрос = остаток.substring(вопрос + 1)
        остаток = остаток.substring(0, вопрос)
    }
    var путь = ""
    val слэш = остаток.indexOf('/')
    if (слэш >= 0) {
        путь = остаток.substring(слэш)
        остаток = остаток.substring(0, слэш)
    }

    val собака = остаток.lastIndexOf('@')
    if (собака >= 0) остаток = остаток.substring(собака + 1)

    var хост = остаток
    var порт = -1
    if (остаток.startsWith("[")) {
        val закрытие = остаток.indexOf(']')
        if (закрытие < 0) return null
        хост = остаток.substring(1, закрытие)
        val хвост = остаток.substring(закрытие + 1)
        if (хвост.startsWith(":")) порт = хвост.substring(1).toIntOrNull() ?: -1
    } else {
        val двоеточие = остаток.lastIndexOf(':')
        if (двоеточие >= 0) {
            val возможный = остаток.substring(двоеточие + 1).toIntOrNull()
            if (возможный != null) {
                порт = возможный
                хост = остаток.substring(0, двоеточие)
            }
        }
    }
    if (хост.isBlank()) return null
    return РазобранныйАдрес(схема, хост, порт, путь, запрос, фрагмент)
}

/**
 * Приводит адрес к виду, пригодному для запроса: русские буквы в домене
 * превращаются в punycode (xn--...), а в пути и параметрах — в процентную
 * запись. HTTP-заголовки и адреса передаются только в латинице.
 */
fun нормализоватьАдрес(адрес: String?): String {
    val исходный = адрес ?: ""
    val части = разобратьАдрес(исходный) ?: return исходный
    val хостAscii = try {
        IDN.toASCII(части.хост)
    } catch (_: Exception) {
        части.хост
    }
    val узел = if (части.порт > 0) л("{1}:{2}", хостAscii, части.порт) else хостAscii
    val путь = проценты(части.путь.ifEmpty { "/" }, БЕЗОПАСНЫЕ_ПУТЬ)
    val запрос = проценты(части.запрос, БЕЗОПАСНЫЕ_ЗАПРОС)
    val фрагмент = проценты(части.фрагмент, "")
    return buildString {
        append(части.схема).append("://").append(узел).append(путь)
        if (запрос.isNotEmpty()) append('?').append(запрос)
        if (фрагмент.isNotEmpty()) append('#').append(фрагмент)
    }
}

/** Переводит типовые сетевые ошибки на русский язык — как `_describe_error`. */
fun перевестиОшибку(причина: String?): String {
    val текст = причина ?: ""
    val переводы = listOf(
        "Connection refused" to л("Соединение отклонено (сервер не слушает порт)"),
        "Name or service not known" to л("Не удалось определить адрес узла (проверьте DNS)"),
        "nodename nor servname provided" to л("Не удалось определить адрес узла"),
        "getaddrinfo failed" to л("Не удалось определить адрес узла"),
        "timed out" to л("Превышено время ожидания ответа"),
        "The read operation timed out" to л("Превышено время ожидания чтения ответа"),
        "No route to host" to л("Узел недоступен (нет маршрута)"),
        "Network is unreachable" to л("Сеть недоступна"),
        "Connection reset by peer" to л("Соединение разорвано удалённой стороной"),
        "certificate verify failed" to л("Ошибка проверки SSL-сертификата"),
        "Certificate path validation failure" to л("Ошибка проверки SSL-сертификата"),
        "PKIX path building failed" to л("Ошибка проверки SSL-сертификата"),
    )
    for ((образец, перевод) in переводы) {
        if (текст.lowercase().contains(образец.lowercase())) return перевод
    }
    return л("Ошибка соединения: {1}", текст)
}

/** Что нужно знать о сайте, чтобы его проверить. */
data class НастройкиСайта(
    val имя: String,
    val url: String,
    val метод: String = "GET",
    val ожидатьКоды: List<Int> = listOf(200),
    val ожидатьТекст: String? = null,
    val таймаутСекунд: Double = 10.0,
    val проверятьSsl: Boolean = true,
    val игнорироватьSslОшибки: Boolean = false,
    val заголовки: Map<String, String> = emptyMap(),
)

/** Что нужно знать о порте, чтобы его проверить. */
data class НастройкиПорта(
    val имя: String,
    val хост: String,
    val порт: Int,
    val таймаутСекунд: Double = 5.0,
)

private fun изJson(объект: JSONObject, ключ: String): String? =
    if (объект.has(ключ) && !объект.isNull(ключ)) объект.optString(ключ).takeIf { it.isNotEmpty() } else null

private fun логическое(объект: JSONObject, ключ: String, поумолчанию: Boolean): Boolean {
    val значение = объект.opt(ключ)
    return when (значение) {
        is Boolean -> значение
        is Number -> значение.toInt() != 0
        is String -> значение.equals("true", true) || значение == "1" || значение.equals("да", true)
        else -> поумолчанию
    }
}

fun сайтИзJson(объект: JSONObject, коды: List<Int>): НастройкиСайта {
    val url = объект.optString("url")
    val заголовки = mutableMapOf<String, String>()
    объект.optJSONObject("заголовки")?.let { раздел ->
        раздел.keys().forEach { ключ ->
            заголовки[безопасныйЗаголовок(ключ)] = безопасныйЗаголовок(раздел.optString(ключ))
        }
    }
    val таймаут = when (val значение = объект.opt("таймаут_секунд")) {
        is Number -> значение.toDouble()
        is String -> значение.replace(',', '.').toDoubleOrNull()
        else -> null
    } ?: 10.0
    return НастройкиСайта(
        имя = изJson(объект, "имя") ?: url.ifEmpty { л("без имени") },
        url = url,
        метод = (изJson(объект, "метод") ?: "GET").uppercase(),
        ожидатьКоды = коды,
        ожидатьТекст = изJson(объект, "ожидать_текст"),
        таймаутСекунд = таймаут,
        проверятьSsl = логическое(объект, "проверять_ssl", true),
        игнорироватьSslОшибки = логическое(объект, "игнорировать_ssl_ошибки", false),
        заголовки = заголовки,
    )
}

fun портИзJson(объект: JSONObject): НастройкиПорта {
    val хост = объект.optString("хост")
    val порт = when (val значение = объект.opt("порт")) {
        is Number -> значение.toInt()
        is String -> значение.trim().toIntOrNull()
        else -> null
    } ?: 0
    val таймаут = when (val значение = объект.opt("таймаут_секунд")) {
        is Number -> значение.toDouble()
        is String -> значение.replace(',', '.').toDoubleOrNull()
        else -> null
    } ?: 5.0
    return НастройкиПорта(
        имя = изJson(объект, "имя") ?: л("{1}:{2}", хост, порт),
        хост = хост,
        порт = порт,
        таймаутСекунд = таймаут,
    )
}

private fun нулевойSslContext(): SSLContext {
    val доверятьВсему = object : X509TrustManager {
        override fun checkClientTrusted(цепочка: Array<X509Certificate>, тип: String) = Unit
        override fun checkServerTrusted(цепочка: Array<X509Certificate>, тип: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    return SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(доверятьВсему), null) }
}

/** Сколько дней осталось до окончания срока действия SSL-сертификата. */
fun днейДоКонцаСертификата(
    хост: String,
    порт: Int = 443,
    таймаутСекунд: Double = 10.0,
    игнорироватьОшибки: Boolean = false,
): Int? {
    return try {
        val контекст = if (игнорироватьОшибки) нулевойSslContext() else SSLContext.getDefault()
        val фабрика = контекст.socketFactory
        val сокет = фабрика.createSocket() as SSLSocket
        сокет.connect(InetSocketAddress(хост, порт), (таймаутСекунд * 1000).toInt())
        сокет.soTimeout = (таймаутСекунд * 1000).toInt()
        if (!игнорироватьОшибки) {
            val параметры = сокет.sslParameters
            параметры.endpointIdentificationAlgorithm = "HTTPS"
            сокет.sslParameters = параметры
        }
        сокет.startHandshake()
        val сертификат = сокет.session.peerCertificates.firstOrNull() as? X509Certificate
        сокет.close()
        if (сертификат == null) null
        else Duration.between(Instant.now(), сертификат.notAfter.toInstant()).toDays().toInt()
    } catch (_: Exception) {
        null
    }
}

private fun результат(
    имя: String,
    вид: String,
    адрес: String,
    доступно: Boolean,
    код: Int? = null,
    задержка: Double? = null,
    ошибка: String? = null,
    sslДней: Int? = null,
): РезультатПроверки = РезультатПроверки(
    время = System.currentTimeMillis() / 1000,
    имя = имя,
    вид = вид,
    адрес = адрес,
    доступно = доступно,
    код = код,
    времяМс = задержка?.let { округлить(it, 1) },
    sslДней = sslДней,
    ошибка = ошибка,
)

/** Проверяет сайт по HTTP/HTTPS. */
fun проверитьСайт(настройки: НастройкиСайта): РезультатПроверки {
    val адресЗапроса = нормализоватьАдрес(настройки.url)
    val разобранный = разобратьАдрес(адресЗапроса)
    val https = разобранный?.схема == "https"
    val таймаутМс = (настройки.таймаутСекунд * 1000).toInt()

    var sslДней: Int? = null
    if (https && настройки.проверятьSsl && разобранный != null) {
        val порт = if (разобранный.порт > 0) разобранный.порт else 443
        sslДней = днейДоКонцаСертификата(
            разобранный.хост,
            порт,
            настройки.таймаутСекунд,
            настройки.игнорироватьSslОшибки,
        )
    }

    val начало = System.nanoTime()
    var соединение: HttpURLConnection? = null
    return try {
        val открытое = URL(адресЗапроса).openConnection() as HttpURLConnection
        соединение = открытое
        открытое.requestMethod = настройки.метод
        открытое.instanceFollowRedirects = true
        открытое.connectTimeout = таймаутМс
        открытое.readTimeout = таймаутМс
        открытое.setRequestProperty("User-Agent", ПОЛЬЗОВАТЕЛЬ_АГЕНТ)
        настройки.заголовки.forEach { (ключ, значение) -> открытое.setRequestProperty(ключ, значение) }
        if (https && настройки.игнорироватьSslОшибки) {
            (открытое as? HttpsURLConnection)?.let { защищённое ->
                защищённое.sslSocketFactory = нулевойSslContext().socketFactory
                защищённое.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
        }

        val читатьТело = настройки.метод == "GET" || настройки.ожидатьТекст != null
        val ответ = try {
            открытое.inputStream
        } catch (_: Exception) {
            открытое.errorStream
        }
        val тело = if (читатьТело && ответ != null) читатьЧасть(ответ) else ByteArray(0)
        val задержка = (System.nanoTime() - начало) / 1_000_000.0
        val код = открытое.responseCode
        val ожидаемые = настройки.ожидатьКоды

        when {
            код in ожидаемые && настройки.ожидатьТекст != null -> {
                val текст = String(тело, кодировка(открытое))
                if (!текст.contains(настройки.ожидатьТекст)) {
                    результат(
                        настройки.имя, "http", настройки.url, false, код, задержка,
                        л("На странице нет текста «{1}»", настройки.ожидатьТекст), sslДней,
                    )
                } else {
                    результат(настройки.имя, "http", настройки.url, true, код, задержка, null, sslДней)
                }
            }

            код in ожидаемые ->
                результат(настройки.имя, "http", настройки.url, true, код, задержка, null, sslДней)

            код >= 400 -> {
                val причина = чистаяПричина(открытое.responseMessage)
                val хвост = if (причина.isNotEmpty()) л(" {1}", причина) else ""
                результат(
                    настройки.имя, "http", настройки.url, false, код, задержка,
                    л("Сервер ответил ошибкой {1}{2}{3}", код, хвост, описаниеКода(код)), sslДней,
                )
            }

            else ->
                результат(
                    настройки.имя, "http", настройки.url, false, код, задержка,
                    л("Код ответа {1}, ожидался {2}{3}", код, ожидаемые.joinToString("/"), описаниеКода(код)),
                    sslДней,
                )
        }
    } catch (_: SocketTimeoutException) {
        результат(
            настройки.имя, "http", настройки.url, false, null, null,
            л("Превышено время ожидания ({1} с)", краткоеЧисло(настройки.таймаутСекунд)),
        )
    } catch (_: UnknownHostException) {
        результат(
            настройки.имя, "http", настройки.url, false, null,
            (System.nanoTime() - начало) / 1_000_000.0,
            л("Не удалось определить адрес узла (проверьте DNS)"), sslДней,
        )
    } catch (ошибка: SSLHandshakeException) {
        результат(настройки.имя, "http", настройки.url, false, null, null, л("Ошибка SSL: {1}", ошибка.message))
    } catch (ошибка: SSLException) {
        результат(настройки.имя, "http", настройки.url, false, null, null, л("Ошибка SSL: {1}", ошибка.message))
    } catch (ошибка: ConnectException) {
        результат(
            настройки.имя, "http", настройки.url, false, null,
            (System.nanoTime() - начало) / 1_000_000.0, перевестиОшибку(ошибка.message), sslДней,
        )
    } catch (ошибка: NoRouteToHostException) {
        результат(
            настройки.имя, "http", настройки.url, false, null,
            (System.nanoTime() - начало) / 1_000_000.0, перевестиОшибку(ошибка.message), sslДней,
        )
    } catch (ошибка: SocketException) {
        результат(
            настройки.имя, "http", настройки.url, false, null,
            (System.nanoTime() - начало) / 1_000_000.0, перевестиОшибку(ошибка.message), sslДней,
        )
    } catch (ошибка: Exception) {
        результат(настройки.имя, "http", настройки.url, false, null, null, л("Ошибка: {1}", ошибка.message))
    } finally {
        соединение?.disconnect()
    }
}

private fun читатьЧасть(поток: java.io.InputStream): ByteArray {
    val буфер = ByteArray(ПРЕДЕЛ_ОТВЕТА)
    var всего = 0
    try {
        поток.use { входящий ->
            while (всего < ПРЕДЕЛ_ОТВЕТА) {
                val прочитано = входящий.read(буфер, всего, ПРЕДЕЛ_ОТВЕТА - всего)
                if (прочитано <= 0) break
                всего += прочитано
            }
        }
    } catch (_: Exception) {
        // тело может обрываться — используем то, что успели прочитать
    }
    return буфер.copyOf(всего)
}

/** Кодировка тела ответа — из заголовка Content-Type, иначе UTF-8. */
private fun кодировка(соединение: HttpURLConnection): java.nio.charset.Charset {
    val тип = соединение.contentType ?: return Charsets.UTF_8
    val часть = тип.split(";").map { it.trim() }.firstOrNull { it.startsWith("charset=", true) }
    val имя = часть?.substringAfter("=")?.trim()?.trim('"') ?: return Charsets.UTF_8
    return try {
        java.nio.charset.Charset.forName(имя)
    } catch (_: Exception) {
        Charsets.UTF_8
    }
}

/** Проверяет доступность TCP-порта (SSH, MySQL, панель управления и т. п.). */
fun проверитьПорт(настройки: НастройкиПорта): РезультатПроверки {
    val адрес = л("{1}:{2}", настройки.хост, настройки.порт)
    val таймаутМс = (настройки.таймаутСекунд * 1000).toInt()
    val начало = System.nanoTime()
    return try {
        Socket().use { сокет ->
            сокет.connect(InetSocketAddress(настройки.хост, настройки.порт), таймаутМс)
            val задержка = (System.nanoTime() - начало) / 1_000_000.0
            результат(настройки.имя, "tcp", адрес, true, null, задержка)
        }
    } catch (_: SocketTimeoutException) {
        результат(
            настройки.имя, "tcp", адрес, false, null, null,
            л("Порт не отвечает ({1} с)", краткоеЧисло(настройки.таймаутСекунд)),
        )
    } catch (_: ConnectException) {
        результат(настройки.имя, "tcp", адрес, false, null, null, л("Соединение отклонено (порт закрыт)"))
    } catch (_: UnknownHostException) {
        результат(настройки.имя, "tcp", адрес, false, null, null, л("Не удалось определить адрес узла"))
    } catch (ошибка: Exception) {
        результат(настройки.имя, "tcp", адрес, false, null, null, л("Ошибка: {1}", ошибка.message))
    }
}
