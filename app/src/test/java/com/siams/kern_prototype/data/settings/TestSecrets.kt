package com.siams.kern_prototype.data.settings

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Шифр для тестов: тот же [AesGcmSecretCipher], но ключ — обычный JCE (в Robolectric нет
 * провайдера AndroidKeyStore). Проверяется код шифрования; получение ключа Keystore — на устройстве.
 */
internal object TestSecrets {
    private val key: SecretKey = newKey()

    fun newKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    fun cipher(k: SecretKey = key, aad: ByteArray = ServerSettingsStore.PASSWORD_AAD): SecretCipher = AesGcmSecretCipher({ k }, aad)
}
