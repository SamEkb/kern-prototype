@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)

package com.siams.kern_prototype.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.domain.model.ServerSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import kotlinx.coroutines.cancel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** R20: пароль S900 на диске — только шифротекстом, в файле, исключённом из резервного копирования. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerSettingsStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val legacyKey = stringPreferencesKey("password")
    private val secret = "s3cr3t-пароль"

    // Хранилища с известными файлами (делегат preferencesDataStore кэширует каталог первого теста).
    private val dir = File(context.cacheDir, "r20-${System.nanoTime()}").apply { mkdirs() }
    private val settingsFile = File(dir, "kern_settings.preferences_pb")
    private val secretsFile = File(dir, "$SECRETS_STORE_NAME.preferences_pb")
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private val settingsData = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { settingsFile }
    private val secretsData = androidx.datastore.preferences.core.PreferenceDataStoreFactory.create(scope = scope) { secretsFile }

    @org.junit.After
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    /** Пароль не встречается в файлах хранилищ ни в каком виде (UTF-8); файлы должны существовать. */
    private fun assertNotOnDisk(plain: String) {
        val needle = plain.toByteArray(Charsets.UTF_8)
        for (f in listOf(settingsFile, secretsFile)) {
            assertTrue("файл ${f.name} записан", f.exists())
            val bytes = f.readBytes()
            val found = (0..bytes.size - needle.size).any { i -> needle.indices.all { bytes[i + it] == needle[it] } }
            assertFalse("пароль открытым текстом в ${f.name}", found)
        }
    }

    private fun store(cipher: SecretCipher = TestSecrets.cipher()) = ServerSettingsStore(settingsData, secretsData, cipher)

    @Test
    fun passwordIsStoredOnlyEncryptedInSecretsFile() = runBlocking {
        val s = store()
        s.save(ServerSettings(host = "h", login = "user", password = secret))
        val read = s.settings.first()
        assertEquals(secret, read.password)
        assertFalse(read.passwordUnavailable)
        assertNull("в общих настройках пароля нет", settingsData.data.first()[legacyKey])
        assertNotOnDisk(secret)
    }

    @Test
    fun legacyPlainPasswordWorksUntilMigratedAndIsThenRemoved() = runBlocking {
        settingsData.edit { it[stringPreferencesKey("host")] = "h"; it[legacyKey] = secret }
        val s = store()
        assertEquals("до переноса прежний пароль работает", secret, s.settings.first().password)
        s.migrateLegacyPassword()
        assertNull(settingsData.data.first()[legacyKey])
        assertEquals(secret, s.settings.first().password)
        assertNotOnDisk(secret)
        s.migrateLegacyPassword() // повторно — без изменений
        assertEquals(secret, s.settings.first().password)
    }

    /** Перенос прерван после записи шифротекста: повтор удаляет открытое значение, шифротекст — не меняет. */
    @Test
    fun interruptedMigrationIsCompletedByRetry() = runBlocking {
        val s = store()
        s.save(ServerSettings(host = "h", login = "user", password = secret))
        settingsData.edit { it[legacyKey] = secret }       // открытое значение не успели удалить
        s.migrateLegacyPassword()
        assertNull(settingsData.data.first()[legacyKey])
        assertEquals(secret, s.settings.first().password)
    }

    /** Сбой шифрования при переносе: открытое значение не удаляется раньше записи шифротекста. */
    @Test
    fun failedMigrationKeepsLegacyPasswordWorking() = runBlocking {
        settingsData.edit { it[legacyKey] = secret }
        val broken = object : SecretCipher {
            override fun encrypt(plain: String): String = error("Keystore недоступен")
            override fun decrypt(token: String): String = error("не должно вызываться")
        }
        try { store(broken).migrateLegacyPassword(); fail() } catch (_: IllegalStateException) {}
        assertEquals(secret, settingsData.data.first()[legacyKey])
        assertEquals("пароль продолжает работать", secret, store().settings.first().password)
    }

    /** Другой ключ (восстановление на другом устройстве, сброс Keystore): пароль «недоступен», без падения. */
    @Test
    fun undecryptablePasswordIsUnavailableNotCrash() = runBlocking {
        store().save(ServerSettings(host = "h", login = "user", password = secret))
        val other = store(TestSecrets.cipher(TestSecrets.newKey()))
        val read = other.settings.first()
        assertEquals("", read.password)
        assertTrue(read.passwordUnavailable)
        assertEquals("остальные настройки на месте", "user", read.login)
        other.save(read.copy(password = "новый", passwordUnavailable = false)) // ввод заново
        assertEquals("новый", other.settings.first().password)
        assertFalse(other.settings.first().passwordUnavailable)
    }

    /** Ошибка шифрования — исключение; открытый пароль не записывается, прежний шифротекст не меняется. */
    @Test
    fun encryptionFailureWritesNothingInPlain() = runBlocking {
        val s = store()
        s.save(ServerSettings(host = "h", login = "user", password = secret))
        val broken = object : SecretCipher {
            override fun encrypt(plain: String): String = error("Keystore недоступен")
            override fun decrypt(token: String): String = TestSecrets.cipher().decrypt(token)
        }
        try {
            store(broken).save(ServerSettings(host = "h2", login = "user", password = "другой-пароль"))
            fail()
        } catch (_: IllegalStateException) {}
        assertNotOnDisk("другой-пароль")
        assertEquals(secret, s.settings.first().password)
        assertEquals("настройки не изменены частично", "h", s.settings.first().host)
    }

    /** Шифротекст привязан к назначению: с другим AAD не расшифровывается. */
    @Test
    fun ciphertextIsBoundToItsPurpose() {
        val key = TestSecrets.newKey()
        val token = TestSecrets.cipher(key).encrypt(secret)
        assertEquals(secret, TestSecrets.cipher(key).decrypt(token))
        try {
            TestSecrets.cipher(key, "другое".toByteArray()).decrypt(token)
            fail()
        } catch (_: Exception) {}
        assertTrue("IV случайный — токены различаются", TestSecrets.cipher(key).encrypt(secret) != token)
    }

    @Test
    fun emptyPasswordRemovesSecret() = runBlocking {
        val s = store()
        s.save(ServerSettings(host = "h", login = "user", password = secret))
        s.save(ServerSettings(host = "h", login = "user", password = ""))
        assertEquals("", s.settings.first().password)
        assertFalse(s.settings.first().passwordUnavailable)
    }

    private fun cfg(host: String, password: String) = ServerSettings(host = host, login = "user", password = password)

    /**
     * Секрет и настройки пишутся в разные файлы. Задержка или обрыв второй записи (в т.ч. «после
     * перезапуска») никогда не дают новый пароль со старым сервером — только «недоступен».
     */
    @Test
    fun partialSaveNeverPairsNewPasswordWithOldServer() = runBlocking {
        val s = store()
        s.save(cfg("a.example", "old"))
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.onBetweenWritesForTest = { entered.complete(Unit); gate.await() }
        val saving = kotlinx.coroutines.GlobalScope.async { s.save(cfg("b.example", "new")) }
        entered.await()
        val mid = s.settings.first()
        assertEquals("a.example", mid.host)
        assertEquals("новый пароль со старым сервером не используется", "", mid.password)
        assertTrue(mid.passwordUnavailable)
        gate.complete(Unit)
        saving.await()
        assertEquals("b.example" to "new", s.settings.first().let { it.host to it.password })

        // Обрыв второй записи: состояние на диске (новый экземпляр — как после перезапуска).
        s.onBetweenWritesForTest = { error("процесс остановлен") }
        try { s.save(cfg("c.example", "newest")); fail() } catch (_: IllegalStateException) {}
        val restarted = store().settings.first()
        assertEquals("b.example", restarted.host)
        assertEquals("", restarted.password)
        assertTrue(restarted.passwordUnavailable)
    }

    /** Перенос прочитал старый пароль → пользователь сохранил новый → перенос не затирает новый. */
    @Test
    fun migrationDoesNotOverwriteConcurrentSave() = runBlocking {
        settingsData.edit { it[stringPreferencesKey("host")] = "a.example"; it[stringPreferencesKey("login")] = "user"; it[legacyKey] = "old" }
        val s = store()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        s.onMigrationReadForTest = { entered.complete(Unit); gate.await() }
        val migrating = kotlinx.coroutines.GlobalScope.async { s.migrateLegacyPassword() }
        entered.await()
        val saving = kotlinx.coroutines.GlobalScope.async { s.save(cfg("a.example", "new")) }
        kotlinx.coroutines.delay(100)
        assertFalse("сохранение ждёт перенос", saving.isCompleted)
        gate.complete(Unit)
        migrating.await(); saving.await()
        assertEquals("new", s.settings.first().password)
        assertNull(settingsData.data.first()[legacyKey])
    }

    /** Штатное восстановление: общие настройки есть, файла секретов нет → «недоступен», а не пустой пароль. */
    @Test
    fun restoreWithoutSecretsFileMarksPasswordUnavailable() = runBlocking {
        val s = store()
        s.save(cfg("a.example", secret))
        secretsData.edit { it.clear() }                             // файл секретов в копию не входил
        val restored = s.settings.first()
        assertEquals("", restored.password)
        assertTrue(restored.passwordUnavailable)

        // Сохранение без ввода пароля — не удаление: остаётся «недоступен».
        s.save(restored.copy(host = "b.example"))
        assertTrue(s.settings.first().passwordUnavailable)
        assertEquals("b.example", s.settings.first().host)

        // Намеренно пустой пароль — не «недоступен».
        s.save(cfg("b.example", ""))
        assertFalse(s.settings.first().passwordUnavailable)
    }
}
