package com.siams.kern_prototype.data.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * R20: шифрование секретов (пароль S900) перед записью на диск.
 * [decrypt] бросает исключение, если расшифровать нельзя (другой ключ, повреждение).
 */
interface SecretCipher {
    fun encrypt(plain: String): String
    fun decrypt(token: String): String
}

/**
 * AES-256-GCM: токен = base64(IV 12 байт ‖ шифротекст+тег). [aad] привязывает токен к назначению
 * (пароль S900 не расшифруется как другой секрет). Ключ — из [key] (в приложении — Android Keystore).
 */
class AesGcmSecretCipher(private val key: () -> SecretKey, private val aad: ByteArray) : SecretCipher {

    override fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // IV генерирует провайдер (Keystore не принимает заданный)
        cipher.updateAAD(aad)
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        require(iv.size == IV_BYTES) { "Неожиданная длина IV: ${iv.size}" }
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    override fun decrypt(token: String): String {
        val bytes = Base64.decode(token, Base64.NO_WRAP)
        require(bytes.size > IV_BYTES) { "Повреждённый секрет" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        cipher.updateAAD(aad)
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/**
 * Ключ в Android Keystore: не извлекается из устройства и не попадает в резервную копию —
 * восстановленный на другом устройстве шифротекст не расшифруется (пароль вводится заново).
 * Без аутентификации пользователя: пароль нужен фоновой отправке (WorkManager).
 */
object KeystoreSecretKey {
    private const val PROVIDER = "AndroidKeyStore"
    const val SERVER_PASSWORD_ALIAS = "kern_server_password_v1"

    fun getOrCreate(alias: String): SecretKey = synchronized(this) {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        gen.generateKey()
    }
}
