package com.siams.kern_prototype.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
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

    /**
     * R02/R08: проблема JPEG-файла или null, если он цел. Критерии отклонения:
     *  - нет файла, пустой, нет маркеров начала (FF D8 FF) и конца (FF D9 в последних байтах);
     *  - baseline-JPEG: проверка всех блоков энтропийных данных [JpegValidator] (коды Хаффмана,
     *    число блоков по размерам кадра, EOI сразу после последнего блока, маркеры рестарта);
     *  - прочие JPEG (прогрессивный и т.п.): декодер Android — неполное/повреждённое изображение
     *    (API 28+: [ImageDecoder.OnPartialImageListener]) или отказ декодирования.
     * Порчу, после которой данные остаются формально корректными, ловит только сверка [sha256] с
     * суммой, записанной при съёмке (так проверяются все снимки v5+ перед отправкой).
     */
    fun jpegProblem(file: File): String? {
        if (!file.isFile) return "файл снимка отсутствует"
        if (file.length() == 0L) return "файл снимка пуст"
        if (!hasJpegMarkers(file)) return "файл снимка повреждён"
        val bytes = runCatching { file.readBytes() }.getOrElse { return "файл снимка не читается" }
        return when (val r = JpegValidator.check(bytes)) {
            JpegValidator.Result.Ok -> null
            is JpegValidator.Result.Corrupt -> "файл снимка повреждён (${r.reason})"
            JpegValidator.Result.Unsupported -> if (decodes(file)) null else "файл снимка повреждён"
        }
    }

    private fun hasJpegMarkers(file: File): Boolean = runCatching {
        if (file.length() < 8) return false
        java.io.RandomAccessFile(file, "r").use { f ->
            val head = ByteArray(3).also { f.readFully(it) }
            if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte() || head[2] != 0xFF.toByte()) return false
            val tailLen = minOf(64L, f.length()).toInt()
            val tail = ByteArray(tailLen)
            f.seek(f.length() - tailLen)
            f.readFully(tail)
            (tailLen - 2 downTo 0).any { tail[it] == 0xFF.toByte() && tail[it + 1] == 0xD9.toByte() }
        }
    }.getOrDefault(false)

    private fun decodes(file: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                val side = maxOf(info.size.width, info.size.height)
                decoder.setTargetSampleSize(maxOf(1, Integer.highestOneBit(maxOf(1, side / VERIFY_MAX_SIDE))))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setOnPartialImageListener { false } // неполное/повреждённое — ошибка
            }
            bmp.recycle()
            true
        } else {
            val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
            BitmapFactory.decodeFile(file.absolutePath, opts)?.also { it.recycle() } != null
        }
    }.getOrDefault(false)

    /** SHA-256 файла (hex). */
    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Удалить файл. Ошибка удаления не бросается (запись в БД уже удалена/переключена —
     * лишний файл безопасен), но пишется в лог. @return true — файла больше нет.
     */
    fun deleteFile(path: String): Boolean {
        val f = File(path)
        val ok = runCatching { !f.exists() || f.delete() }.getOrDefault(false)
        if (!ok) Log.w(TAG, "Не удалось удалить файл $path — остаётся на диске")
        return ok
    }

    /** Удалить все файлы долбления (каскад при удалении, §7). @return true — каталога больше нет. */
    fun deleteDrillingDir(drillingId: String): Boolean {
        val dir = File(root, drillingId)
        val ok = runCatching { !dir.exists() || dir.deleteRecursively() }.getOrDefault(false)
        if (!ok) Log.w(TAG, "Не удалось полностью удалить каталог ${dir.path}")
        return ok
    }

    private companion object {
        const val TAG = "FileStorage"
        const val VERIFY_MAX_SIDE = 1024
    }

    /** Суммарный занятый объём в байтах (индикация места, §11). */
    fun usedBytes(): Long =
        root.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
}
