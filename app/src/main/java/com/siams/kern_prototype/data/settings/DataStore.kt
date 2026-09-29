package com.siams.kern_prototype.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/** Единый Preferences DataStore приложения (настройки сервера + профили камеры). */
val Context.kernDataStore: DataStore<Preferences> by preferencesDataStore(name = "kern_settings")
