package com.siams.kern_prototype.data.storage

import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * R11: полная проверка PNG эталона FFC до его использования. Заголовка и размеров мало —
 * повреждённые пиксельные данные при декодировании могут молча превратиться в «частичный»
 * кадр. Поэтому проверяется вся структура:
 *  - сигнатура, порядок фрагментов, CRC каждого фрагмента, наличие IEND;
 *  - размеры в пределах [MAX_SIDE];
 *  - IDAT распаковывается целиком, объём совпадает с ожидаемым по IHDR (с учётом Adam7);
 *  - затем полное декодирование в Bitmap с совпадением размеров.
 */
object PngCheck {

    /** Эталон FFC хранится уменьшенным (512 px), запас — до 2048 по стороне. */
    const val MAX_SIDE = 2048

    private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** @return null — PNG целый; иначе причина отказа. */
    fun problem(bytes: ByteArray): String? {
        if (bytes.size < SIGNATURE.size || !bytes.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) {
            return "не PNG"
        }
        val buf = ByteBuffer.wrap(bytes, SIGNATURE.size, bytes.size - SIGNATURE.size)
        var header: IntArray? = null // width, height, bitDepth, colorType, interlace
        val idat = ByteArrayOutputStream()
        var ended = false
        while (!ended) {
            if (buf.remaining() < 12) return "файл обрезан"
            val len = buf.int
            if (len < 0 || len > buf.remaining() - 8) return "неверная длина фрагмента"
            val typeAndData = ByteArray(4 + len).also { buf.get(it) }
            val crc = buf.int
            val actual = CRC32().apply { update(typeAndData) }.value.toInt()
            if (crc != actual) return "повреждены данные (CRC)"
            val type = String(typeAndData, 0, 4, Charsets.US_ASCII)
            if (header == null && type != "IHDR") return "нет заголовка IHDR"
            when (type) {
                "IHDR" -> {
                    if (len != 13 || header != null) return "неверный IHDR"
                    val h = ByteBuffer.wrap(typeAndData, 4, 13)
                    val width = h.int
                    val height = h.int
                    val bitDepth = h.get().toInt() and 0xFF
                    val colorType = h.get().toInt() and 0xFF
                    h.get(); h.get() // compression, filter
                    val interlace = h.get().toInt() and 0xFF
                    if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) return "недопустимый размер ${width}x$height"
                    header = intArrayOf(width, height, bitDepth, colorType, interlace)
                }
                "IDAT" -> idat.write(typeAndData, 4, len)
                "IEND" -> ended = true
            }
        }
        val (width, height, bitDepth, colorType, interlace) = header ?: return "нет заголовка IHDR"
        val channels = when (colorType) { 0 -> 1; 2 -> 3; 3 -> 1; 4 -> 2; 6 -> 4; else -> return "неверный тип цвета" }
        val expected = expectedRawSize(width, height, channels * bitDepth, interlace == 1)
        val inflated = inflatedSize(idat.toByteArray(), expected + 1) ?: return "повреждены сжатые данные"
        if (inflated != expected) return "неполные данные изображения"

        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "не декодируется"
        val sizeOk = bmp.width == width && bmp.height == height
        bmp.recycle()
        return if (sizeOk) null else "не декодируется"
    }

    private fun expectedRawSize(width: Int, height: Int, bitsPerPixel: Int, adam7: Boolean): Long {
        fun rows(w: Int, h: Int): Long = if (w == 0 || h == 0) 0 else h * (1 + (w.toLong() * bitsPerPixel + 7) / 8)
        if (!adam7) return rows(width, height)
        val passes = arrayOf(
            intArrayOf(0, 0, 8, 8), intArrayOf(4, 0, 8, 8), intArrayOf(0, 4, 4, 8), intArrayOf(2, 0, 4, 4),
            intArrayOf(0, 2, 2, 4), intArrayOf(1, 0, 2, 2), intArrayOf(0, 1, 1, 2),
        )
        return passes.sumOf { (xs, ys, dx, dy) ->
            rows((width - xs + dx - 1).coerceAtLeast(0) / dx, (height - ys + dy - 1).coerceAtLeast(0) / dy)
        }
    }

    /** Размер распакованного zlib-потока (не больше [limit]); null — поток повреждён/не завершён. */
    private fun inflatedSize(data: ByteArray, limit: Long): Long? {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val out = ByteArray(64 * 1024)
            var total = 0L
            while (!inflater.finished()) {
                val n = inflater.inflate(out)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                total += n
                if (total > limit) return total
            }
            if (inflater.remaining > 0) null else total
        } catch (_: DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }
}
