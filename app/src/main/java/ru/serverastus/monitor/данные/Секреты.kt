package ru.serverastus.monitor.данные

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Шифрование секретов; в тестах подменяется обратимой реализацией без Keystore. */
interface Шифровальщик {
    fun зашифровать(текст: String): String

    /** Возвращает null, если значение испорчено или ключ больше недоступен. */
    fun расшифровать(значение: String): String?
}

/**
 * Шифрование через Android Keystore: ключ живёт в системном хранилище и в
 * файлы приложения не попадает. При удалении данных приложения ключ пропадает
 * вместе с ними — тогда пароль придётся ввести в мастере заново.
 */
class ШифровальщикКейстора(private val псевдоним: String = "server_monitor_secrets") : Шифровальщик {

    override fun зашифровать(текст: String): String {
        val шифр = Cipher.getInstance(ПРЕОБРАЗОВАНИЕ)
        шифр.init(Cipher.ENCRYPT_MODE, ключ())
        val данные = шифр.doFinal(текст.toByteArray(Charsets.UTF_8))
        return ПРЕФИКС + Base64.getEncoder().encodeToString(шифр.iv + данные)
    }

    override fun расшифровать(значение: String): String? {
        if (!значение.startsWith(ПРЕФИКС)) return null
        return try {
            val байты = Base64.getDecoder().decode(значение.substring(ПРЕФИКС.length))
            val шифр = Cipher.getInstance(ПРЕОБРАЗОВАНИЕ)
            шифр.init(Cipher.DECRYPT_MODE, ключ(), GCMParameterSpec(ТЕГ_БИТ, байты, 0, ВЕКТОР_БАЙТ))
            String(шифр.doFinal(байты, ВЕКТОР_БАЙТ, байты.size - ВЕКТОР_БАЙТ), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun ключ(): SecretKey {
        val хранилище = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (хранилище.getKey(псевдоним, null) as? SecretKey)?.let { return it }
        val генератор = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        генератор.init(
            KeyGenParameterSpec.Builder(псевдоним, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return генератор.generateKey()
    }

    private companion object {
        const val ПРЕОБРАЗОВАНИЕ = "AES/GCM/NoPadding"
        const val ПРЕФИКС = "kst-v1:"
        const val ВЕКТОР_БАЙТ = 12
        const val ТЕГ_БИТ = 128
    }
}

/**
 * Пароли серверов отдельным файлом: в самом `config.json` остаётся только
 * ссылка `секрет_id`. Хранилище не требует контекста, поэтому его работу
 * можно проверить обычными JVM-тестами.
 */
class ХранилищеСекретов(private val файл: File, private val шифровальщик: Шифровальщик) {

    private var данные: JSONObject = прочитать()

    fun сохранить(ид: String, секрет: String) {
        if (ид.isBlank()) return
        if (секрет.isEmpty()) {
            удалить(ид)
            return
        }
        данные.put(ид, шифровальщик.зашифровать(секрет))
        записать()
    }

    fun получить(ид: String): String? {
        if (ид.isBlank()) return null
        val значение = данные.optString(ид)
        if (значение.isEmpty()) return null
        return шифровальщик.расшифровать(значение)
    }

    fun удалить(ид: String) {
        if (ид.isBlank() || !данные.has(ид)) return
        данные.remove(ид)
        записать()
    }

    fun очистить() {
        данные = JSONObject()
        if (файл.isFile) файл.delete()
    }

    /** Идентификаторы, для которых сохранён секрет (для проверок и тестов). */
    fun имена(): List<String> = данные.keys().asSequence().toList()

    private fun прочитать(): JSONObject = try {
        if (файл.isFile) JSONObject(файл.readText(Charsets.UTF_8)) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun записать() {
        try {
            файл.parentFile?.mkdirs()
            файл.writeText(данные.toString(), Charsets.UTF_8)
        } catch (_: Exception) {
            // секреты — вспомогательный файл, его сбой не должен ломать настройки
        }
    }
}
