package com.siams.kern_prototype

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Настоящие JPEG для тестов (требуют `@GraphicsMode(NATIVE)` в Robolectric). */
object TestJpeg {
    /** Шумное изображение [w]×[h] — энтропийные данные занимают почти весь файл. */
    fun bytes(w: Int = 96, h: Int = 64, seed: Int = 1): ByteArray {
        val rnd = Random(seed)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bmp.setPixels(IntArray(w * h) { 0xFF000000.toInt() or rnd.nextInt(0xFFFFFF) }, 0, w, 0, 0, w, h)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Порча середины файла: начало (SOI) и конец (EOI) сохранены. */
    fun corruptMiddle(src: ByteArray, how: String): ByteArray {
        val mid = src.size / 2
        return when (how) {
            // Вырезан кусок энтропийных данных — длина и маркеры конца на месте.
            "cut" -> src.copyOfRange(0, mid) + src.copyOfRange(mid + src.size / 8, src.size)
            // Преждевременный маркер конца изображения посреди данных.
            "eoi" -> src.copyOf().also { it[mid] = 0xFF.toByte(); it[mid + 1] = 0xD9.toByte() }
            // Инверсия байтов в середине — формально данные могут остаться «корректными».
            "flip" -> src.copyOf().also { for (i in mid until mid + 16) it[i] = (it[i].toInt() xor 0x5A).toByte() }
            else -> error(how)
        }
    }
}
