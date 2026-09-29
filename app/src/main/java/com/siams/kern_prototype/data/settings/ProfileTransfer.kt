package com.siams.kern_prototype.data.settings

import android.content.Context
import android.util.Base64
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Один профиль + встроенный эталон FFC (PNG в base64), чтобы файл был самодостаточным (§4.2). */
@Serializable
data class ProfileEntry(
    val profile: CameraProfile,
    val ffcPngBase64: String? = null,
)

/** Файл переноса профилей: оба режима освещения разом (§4.2). */
@Serializable
data class ProfileBundle(
    val version: Int = 1,
    val entries: List<ProfileEntry>,
)

/**
 * Экспорт/импорт профилей камеры (§4.2). Один JSON-файл содержит ВСЕ ручные параметры
 * обоих профилей (дневной/УФ) и их эталонные кадры FFC (встроены как base64), чтобы
 * воспроизвести идентичные условия съёмки на другом экземпляре фотокабины.
 */
@Singleton
class ProfileTransfer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
    private val store: CameraProfileStore,
) {
    /** Собрать JSON со всеми профилями и их эталонами FFC. */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val entries = LampType.entries.map { lamp ->
            val p = store.read(lamp)
            val ffc = p.ffcTemplatePath
                .takeIf { it.isNotBlank() }
                ?.let { path -> runCatching { File(path).readBytes() }.getOrNull() }
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            ProfileEntry(profile = p, ffcPngBase64 = ffc)
        }
        json.encodeToString(ProfileBundle.serializer(), ProfileBundle(entries = entries))
    }

    /**
     * Применить импортированный JSON: распаковать эталоны FFC в локальные файлы, переписать
     * пути и сохранить профили (с новой отметкой времени). Возвращает число применённых профилей.
     */
    suspend fun importJson(text: String): Int = withContext(Dispatchers.IO) {
        val bundle = json.decodeFromString(ProfileBundle.serializer(), text)
        val now = System.currentTimeMillis()
        bundle.entries.forEach { e ->
            val ffcPath = e.ffcPngBase64?.let { b64 ->
                val bytes = Base64.decode(b64, Base64.NO_WRAP)
                val f = File(context.filesDir, "ffc/ffc_${e.profile.lampType.name}.png")
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
                f.absolutePath
            }.orEmpty()
            store.save(e.profile.copy(ffcTemplatePath = ffcPath, lastModifiedAt = now))
        }
        bundle.entries.size
    }
}
