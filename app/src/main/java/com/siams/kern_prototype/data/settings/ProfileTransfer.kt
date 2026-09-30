package com.siams.kern_prototype.data.settings

import android.content.Context
import android.util.Base64
import com.siams.kern_prototype.camera.FfcQuality
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.data.storage.FfcFiles
import com.siams.kern_prototype.data.storage.PngCheck
import com.siams.kern_prototype.data.storage.writeAtomically
import com.siams.kern_prototype.domain.model.LampType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    /**
     * Собрать JSON со всеми профилями и их эталонами FFC. Если эталон в профиле задан, но
     * отсутствует или повреждён — ошибка: неполный профиль без коррекции не экспортируется.
     */
    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val entries = LampType.entries.map { lamp ->
            val p = store.read(lamp)
            val ffc = p.ffcTemplatePath.takeIf { it.isNotBlank() }?.let { path ->
                val bytes = runCatching { File(path).readBytes() }.getOrNull()
                    ?: throw IllegalStateException(
                        "Эталон FFC профиля «${lamp.ruLabel}» не найден. Экспорт отменён: " +
                            "переснимите эталон и сохраните профиль.",
                    )
                PngCheck.problem(bytes)?.let {
                    throw IllegalStateException("Эталон FFC профиля «${lamp.ruLabel}» повреждён ($it). Экспорт отменён.")
                }
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            ProfileEntry(profile = p, ffcPngBase64 = ffc)
        }
        json.encodeToString(ProfileBundle.serializer(), ProfileBundle(entries = entries))
    }

    /**
     * Применить импортированный JSON (R11): сначала ПОЛНАЯ проверка файла (версия, профили,
     * эталоны PNG), затем эталоны пишутся в НОВЫЕ файлы (текущие не перезаписываются), и
     * только потом все профили сохраняются одной записью. Любая ошибка до этого момента
     * оставляет текущую конфигурацию нетронутой. Возвращает число применённых профилей.
     */
    suspend fun importJson(text: String): Int = withContext(Dispatchers.IO) {
        val bundle = runCatching { json.decodeFromString(ProfileBundle.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("Файл не является профилем камеры", it) }
        val staged = validate(bundle)
        val now = System.currentTimeMillis()
        val written = mutableListOf<File>()
        try {
            val profiles = staged.map { (profile, png) ->
                val ffcPath = png?.let { bytes ->
                    FfcFiles.newFile(context, profile.lampType)
                        .also { f -> writeAtomically(f) { it.write(bytes); true }; written += f }
                        .absolutePath
                }.orEmpty()
                profile.copy(ffcTemplatePath = ffcPath, lastModifiedAt = now)
            }
            store.saveAll(profiles)
            profiles.size
        } catch (t: Throwable) {
            withContext(NonCancellable) { discardUnreferenced(written) }
            throw t
        }
    }

    /**
     * R11: исключение (в т.ч. отмена ожидания DataStore) не доказывает, что профили не
     * сохранены. Удаляем только те новые эталоны, на которые НЕ ссылается ни один профиль в
     * хранилище; если хранилище прочитать не удалось — не удаляем ничего.
     */
    private suspend fun discardUnreferenced(written: List<File>) {
        if (written.isEmpty()) return
        val referenced = runCatching { LampType.entries.map { store.read(it).ffcTemplatePath }.toSet() }
            .getOrNull() ?: return
        written.filter { it.absolutePath !in referenced }.forEach { it.delete() }
    }

    /**
     * R22: пригодность импортируемого шаблона — до записи файлов и изменения профилей. Шаблон
     * загружается тем же способом, что и для коррекции снимков. Проверяются прозрачность пикселей, нулевые каналы и
     * усиление коррекции; пересвет и сигнал исходного кадра по шаблону не восстановить.
     */
    private fun checkTemplateQuality(lamp: LampType, label: String, bytes: ByteArray) {
        val bmp = FfcFiles.decodeTemplateBytes(bytes)
            ?: throw IllegalArgumentException("Профиль «$label»: эталон FFC не читается как шаблон коррекции")
        val report = try { FfcQuality.evaluateTemplate(lamp, bmp) } finally { bmp.recycle() }
        if (!report.accepted) {
            throw IllegalArgumentException(
                "Профиль «$label»: эталон FFC отклонён: ${report.problems.joinToString("; ")}" +
                    (if (report.thresholds.provisional) " (пороги предварительные)" else "") +
                    ". Импорт отменён, текущие профили не изменены.",
            )
        }
    }

    /** R11: проверить весь bundle до каких-либо изменений; вернуть профили с декодированными PNG. */
    private fun validate(bundle: ProfileBundle): List<Pair<CameraProfile, ByteArray?>> {
        require(bundle.version == 1) { "Неподдерживаемая версия файла профилей: ${bundle.version}" }
        require(bundle.entries.isNotEmpty()) { "В файле нет профилей" }
        val lamps = bundle.entries.map { it.profile.lampType }
        require(lamps.size == lamps.toSet().size) { "В файле повторяется тип освещения" }
        return bundle.entries.map { e ->
            val p = e.profile
            val label = p.lampType.ruLabel
            require(p.exposureTimeNs > 0 && p.iso > 0) { "Профиль «$label»: неверные выдержка/усиление" }
            require(p.wbRedGain > 0f && p.wbGreenEvenGain > 0f && p.wbGreenOddGain > 0f && p.wbBlueGain > 0f) {
                "Профиль «$label»: неверный баланс белого"
            }
            require(p.gamma in 20..180) { "Профиль «$label»: гамма вне диапазона 20–180" }
            require(p.jpegQuality in 1..100) { "Профиль «$label»: качество JPEG вне диапазона 1–100" }
            val png = e.ffcPngBase64?.let { b64 ->
                val bytes = runCatching { Base64.decode(b64, Base64.NO_WRAP) }.getOrNull()
                    ?: throw IllegalArgumentException("Профиль «$label»: повреждён эталон FFC")
                PngCheck.problem(bytes)?.let { throw IllegalArgumentException("Профиль «$label»: эталон FFC — $it") }
                checkTemplateQuality(p.lampType, label, bytes)
                bytes
            }
            p to png
        }
    }
}
