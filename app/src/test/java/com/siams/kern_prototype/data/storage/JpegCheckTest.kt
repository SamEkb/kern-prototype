package com.siams.kern_prototype.data.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.TestJpeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * R02: целостность JPEG. Настоящие JPEG (`Bitmap.compress`), порча середины при сохранённых
 * маркерах начала и конца. Детерминированные критерии — [JpegValidator]; любую порчу ловит SHA-256.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JpegCheckTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fs = FileStorage(context)
    private val file = File(context.filesDir, "check.jpg")

    private fun problem(bytes: ByteArray): String? = fs.jpegProblem(file.apply { writeBytes(bytes) })

    @Test
    fun realJpegPasses() {
        for (seed in 1..5) assertNull(problem(TestJpeg.bytes(320, 240, seed)))
        assertEquals(JpegValidator.Result.Ok, JpegValidator.check(TestJpeg.bytes(33, 17))) // края не кратны блоку
    }

    @Test
    fun middleCutAndPrematureEndAreAlwaysRejected() {
        for (seed in 1..20) for (how in listOf("cut", "eoi")) {
            val bad = TestJpeg.corruptMiddle(TestJpeg.bytes(320, 240, seed), how)
            // Начало и конец файла целы — старая проверка «по маркерам» такие файлы пропускала.
            assertEquals(0xFF.toByte(), bad[0]); assertEquals(0xD9.toByte(), bad.last())
            assertNotNull("seed=$seed $how", problem(bad))
        }
    }

    @Test
    fun anyChangeIsCaughtBySha256() {
        val good = TestJpeg.bytes(320, 240)
        val sha = fs.sha256(file.apply { writeBytes(good) })
        for (how in listOf("cut", "eoi", "flip")) {
            val bad = TestJpeg.corruptMiddle(good, how)
            assertTrue(how, fs.sha256(file.apply { writeBytes(bad) }) != sha)
        }
    }

    @Test
    fun truncatedAndEmptyAreRejected() {
        val good = TestJpeg.bytes(320, 240)
        assertNotNull(problem(good.copyOf(good.size / 2)))
        assertEquals("файл снимка пуст", problem(ByteArray(0)))
        file.delete()
        assertEquals("файл снимка отсутствует", fs.jpegProblem(file))
    }

    @Test
    fun nonBaselineJpegIsLeftToPlatformDecoder() {
        val sof2 = TestJpeg.bytes().copyOf().also { b ->
            val i = (0 until b.size - 1).first { b[it] == 0xFF.toByte() && b[it + 1] == 0xC0.toByte() }
            b[i + 1] = 0xC2.toByte() // помечен как прогрессивный
        }
        assertEquals(JpegValidator.Result.Unsupported, JpegValidator.check(sof2))
    }
}
