package com.siams.kern_prototype.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.siams.kern_prototype.domain.model.ServerSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val HOST = stringPreferencesKey("host")
        val PORT = intPreferencesKey("port")
        val HTTPS = booleanPreferencesKey("https")
        val LOGIN = stringPreferencesKey("login")
        val PASSWORD = stringPreferencesKey("password")
        val FIELDS_LOADED_AT = longPreferencesKey("fields_loaded_at")
    }

    val settings: Flow<ServerSettings> = context.kernDataStore.data.map { p ->
        ServerSettings(
            host = p[Keys.HOST] ?: "",
            port = p[Keys.PORT] ?: 8080,
            useHttps = p[Keys.HTTPS] ?: false,
            login = p[Keys.LOGIN] ?: "",
            password = p[Keys.PASSWORD] ?: "",
            fieldsConfigLoadedAt = p[Keys.FIELDS_LOADED_AT] ?: 0L,
        )
    }

    suspend fun save(s: ServerSettings) {
        context.kernDataStore.edit { p ->
            p[Keys.HOST] = s.host
            p[Keys.PORT] = s.port
            p[Keys.HTTPS] = s.useHttps
            p[Keys.LOGIN] = s.login
            p[Keys.PASSWORD] = s.password
        }
    }

    suspend fun setFieldsLoadedAt(ts: Long) {
        context.kernDataStore.edit { it[Keys.FIELDS_LOADED_AT] = ts }
    }
}
