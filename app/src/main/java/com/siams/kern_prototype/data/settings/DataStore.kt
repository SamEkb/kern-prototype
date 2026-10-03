package com.siams.kern_prototype.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** Единый Preferences DataStore приложения (настройки сервера + профили камеры). */
val Context.kernDataStore: DataStore<Preferences> by preferencesDataStore(name = "kern_settings")

/**
 * R20: секреты (зашифрованный пароль S900) — в ОТДЕЛЬНОМ файле `datastore/kern_secrets.preferences_pb`,
 * исключённом из резервного копирования и переноса (res/xml/backup_rules, data_extraction_rules).
 */
val Context.kernSecretsStore: DataStore<Preferences> by preferencesDataStore(name = SECRETS_STORE_NAME)

const val SECRETS_STORE_NAME = "kern_secrets"
