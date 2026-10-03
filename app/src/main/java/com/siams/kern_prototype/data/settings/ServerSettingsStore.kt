package com.siams.kern_prototype.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import android.util.Log
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.siams.kern_prototype.domain.model.ServerSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Настройки подключения к s900 (§5.1).
 *
 * R20 — пароль:
 *  - на диске только шифротекстом ([cipher]; в приложении — ключ Android Keystore) в отдельном файле
 *    `kern_secrets` ([kernSecretsStore]), исключённом из резервного копирования и переноса;
 *  - секрет ПРИВЯЗАН к назначению (сервер + логин): внутри шифротекста — ключ назначения. Если он не
 *    совпадает с текущими настройками, пароль «недоступен». Поэтому промежуточное или оборванное
 *    состояние двух записей (секрет и настройки пишутся в разные файлы) никогда не даёт новый пароль
 *    со старым сервером или старый пароль с новым — только отказ входа;
 *  - признак ожидаемого пароля `password_set` хранится в общих (копируемых) настройках: пароль задан,
 *    а секрета нет (восстановление из копии без файла секретов) или он не расшифровывается/не
 *    подходит — [ServerSettings.passwordUnavailable]. Намеренно пустой пароль — `password_set = false`;
 *  - сохранение и перенос открытого пароля прежних версий ([migrateLegacyPassword]) — под одним
 *    замком. Перенос: сначала шифротекст, затем удаление открытого значения — прерывание безопасно.
 */
@Singleton
class ServerSettingsStore internal constructor(
    private val settingsData: DataStore<Preferences>,
    private val secretsData: DataStore<Preferences>,
    private val cipher: SecretCipher,
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context, AesGcmSecretCipher({ KeystoreSecretKey.getOrCreate(KeystoreSecretKey.SERVER_PASSWORD_ALIAS) }, PASSWORD_AAD),
    )

    internal constructor(context: Context, cipher: SecretCipher) : this(context.kernDataStore, context.kernSecretsStore, cipher)

    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val HTTPS = booleanPreferencesKey("https")
        val LOGIN = stringPreferencesKey("login")
        /** Открытый пароль прежних версий — только чтение для переноса. */
        val LEGACY_PASSWORD = stringPreferencesKey("password")
        /** Пароль задан (его секрет должен быть в хранилище секретов). */
        val PASSWORD_SET = booleanPreferencesKey("password_set")
        val FIELDS_LOADED_AT = longPreferencesKey("fields_loaded_at")
    }

    private object SecretKeys {
        val PASSWORD = stringPreferencesKey("server_password")
    }

    /** Сохранение и перенос — строго по одному. */
    private val writeLock = Mutex()

    val settings: Flow<ServerSettings> = combine(settingsData.data, secretsData.data) { p, secrets ->
        val base = ServerSettings(
            host = p[Keys.HOST] ?: "",
            port = p[Keys.PORT] ?: 8080,
            useHttps = p[Keys.HTTPS] ?: false,
            login = p[Keys.LOGIN] ?: "",
            fieldsConfigLoadedAt = p[Keys.FIELDS_LOADED_AT] ?: 0L,
        )
        val (password, unavailable) = resolvePassword(p, secrets[SecretKeys.PASSWORD], base.destination().key)
        base.copy(password = password, passwordUnavailable = unavailable)
    }

    private fun resolvePassword(p: Preferences, token: String?, destination: String): Pair<String, Boolean> {
        p[Keys.LEGACY_PASSWORD]?.let { return it to false } // ещё не перенесён
        if (p[Keys.PASSWORD_SET] != true) return "" to false // пароля нет намеренно
        if (token == null) return unavailable("секрета нет (восстановление без файла секретов?)")
        val (boundTo, password) = runCatching { unpack(cipher.decrypt(token)) }
            .getOrElse { return unavailable("не расшифровывается: ${it.javaClass.simpleName}") }
        if (boundTo != destination) return unavailable("секрет относится к другому назначению")
        return password to false
    }

    private fun unavailable(why: String): Pair<String, Boolean> {
        runCatching { Log.w(TAG, "Пароль S900 недоступен — нужно ввести заново: $why") }
        return "" to true
    }

    /**
     * Сохранить настройки. Пароль — только шифротекстом, привязанным к назначению [s] (ошибка
     * шифрования — исключение, ничего не записано). [ServerSettings.passwordUnavailable] = true —
     * пароль недоступен и не введён заново: секрет и признак не меняются (это не удаление пароля).
     */
    suspend fun save(s: ServerSettings) = writeLock.withLock {
        if (s.passwordUnavailable) {
            settingsData.edit { p -> putConnection(p, s) }
            return@withLock
        }
        val token = s.password.takeIf { it.isNotEmpty() }?.let { cipher.encrypt(pack(s.destination().key, it)) }
        secretsData.edit { if (token != null) it[SecretKeys.PASSWORD] = token else it.remove(SecretKeys.PASSWORD) }
        onBetweenWritesForTest?.invoke()
        settingsData.edit { p ->
            putConnection(p, s)
            p[Keys.PASSWORD_SET] = token != null
            p.remove(Keys.LEGACY_PASSWORD)
        }
    }

    private fun putConnection(p: androidx.datastore.preferences.core.MutablePreferences, s: ServerSettings) {
        p[Keys.HOST] = s.host
        p[Keys.PORT] = s.port
        p[Keys.HTTPS] = s.useHttps
        p[Keys.LOGIN] = s.login
    }

    /** R20: перенести открытый пароль прежней версии в зашифрованное хранилище (идемпотентно). */
    suspend fun migrateLegacyPassword() = writeLock.withLock {
        val p = settingsData.data.first()
        val legacy = p[Keys.LEGACY_PASSWORD] ?: return@withLock
        onMigrationReadForTest?.invoke()
        val destination = ServerSettings(
            host = p[Keys.HOST] ?: "", port = p[Keys.PORT] ?: 8080, useHttps = p[Keys.HTTPS] ?: false, login = p[Keys.LOGIN] ?: "",
        ).destination().key
        if (legacy.isNotEmpty()) {
            val token = cipher.encrypt(pack(destination, legacy))
            secretsData.edit { it[SecretKeys.PASSWORD] = token }
        }
        settingsData.edit {
            it.remove(Keys.LEGACY_PASSWORD)
            it[Keys.PASSWORD_SET] = legacy.isNotEmpty()
        }
    }

    /** Точки для тестов: между записью секрета и настроек; после чтения при переносе. В приложении null. */
    @androidx.annotation.VisibleForTesting
    internal var onBetweenWritesForTest: (suspend () -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    internal var onMigrationReadForTest: (suspend () -> Unit)? = null

    suspend fun setFieldsLoadedAt(ts: Long) {
        settingsData.edit { it[Keys.FIELDS_LOADED_AT] = ts }
    }

    companion object {
        private const val TAG = "ServerSettingsStore"

        /** Содержимое секрета: назначение и пароль (длина назначения — префиксом). */
        internal fun pack(destination: String, password: String) = "${destination.length}:$destination$password"

        internal fun unpack(payload: String): Pair<String, String> {
            val colon = payload.indexOf(':')
            val n = payload.substring(0, colon).toInt()
            val start = colon + 1
            return payload.substring(start, start + n) to payload.substring(start + n)
        }
        /** Привязка шифротекста к назначению (см. [AesGcmSecretCipher]). */
        internal val PASSWORD_AAD = "kern:s900-password:v1".toByteArray(Charsets.UTF_8)
    }
}
