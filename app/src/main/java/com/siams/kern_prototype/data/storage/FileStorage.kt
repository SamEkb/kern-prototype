package com.siams.kern_prototype.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Хранение фото во внутреннем `filesDir` (§3, §7) — данные защищены до отправки.
 * Разрешения на внешнее хранилище не нужны.
 *
 * Раскладка: `filesDir/photos/<drillingId>/<layerId>.jpg`.
 */
@Singleton
class FileStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val root: File = File(context.filesDir, "photos").apply { mkdirs() }

    fun drillingDir(drillingId: String): File =
        File(root, drillingId).apply { mkdirs() }

    /** Целевой файл для слоя. Файл создаётся вызывающим (камерой). */
    fun layerFile(drillingId: String, layerId: String): File =
        File(drillingDir(drillingId), "$layerId.jpg")

    /** Файл миниатюры рядом с оригиналом. */
    fun thumbFileFor(original: File): File =
        File(original.parentFile, "${original.nameWithoutExtension}_thumb.jpg")

    /**
     * Сгенерировать уменьшенную миниатюру ([maxDim] px по длинной стороне) для быстрых
     * превью (§11). Декодирование с inSampleSize — без загрузки полного кадра в память.
     * @return true при успехе.
     */
    fun writeThumbnail(src: File, dst: File, maxDim: Int = 512): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@runCatching false
        var sample = 1
        while (w / (sample * 2) >= maxDim && h / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(src.absolutePath, opts) ?: return@runCatching false
        dst.parentFile?.mkdirs()
        dst.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        true
    }.getOrDefault(false)

    fun deleteFile(path: String) {
        runCatching { File(path).takeIf { it.exists() }?.delete() }
    }

    /** Удалить все файлы долбления (каскад при удалении, §7). */
    fun deleteDrillingDir(drillingId: String) {
        runCatching { drillingDir(drillingId).deleteRecursively() }
    }

    /** Суммарный занятый объём в байтах (индикация места, §11). */
    fun usedBytes(): Long =
        root.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
}
