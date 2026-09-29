package com.siams.kern_prototype.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Глобальные профили камеры «Дневной» и «УФ» (§6.3). Хранятся в DataStore,
 * переживают перезапуск. Дефолты — [CameraProfile.defaultFor] до калибровки.
 */
@Singleton
open class CameraProfileStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private fun key(lamp: LampType) = stringPreferencesKey("camera_profile_${lamp.name}")

    fun profile(lamp: LampType): Flow<CameraProfile> =
        context.kernDataStore.data.map { p ->
            p[key(lamp)]?.let { runCatching { json.decodeFromString<CameraProfile>(it) }.getOrNull() }
                ?: CameraProfile.defaultFor(lamp)
        }

    suspend fun save(profile: CameraProfile) {
        context.kernDataStore.edit { p ->
            p[key(profile.lampType)] = json.encodeToString(CameraProfile.serializer(), profile)
        }
    }

    /** R11: сохранить несколько профилей одной записью DataStore — всё или ничего. */
    open suspend fun saveAll(profiles: List<CameraProfile>) {
        context.kernDataStore.edit { p ->
            profiles.forEach { p[key(it.lampType)] = json.encodeToString(CameraProfile.serializer(), it) }
        }
    }

    /** Разовое чтение для CameraController при применении профиля. */
    open suspend fun read(lamp: LampType): CameraProfile = profile(lamp).first()
}
