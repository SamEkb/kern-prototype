package com.siams.kern_prototype.data.settings

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.siams.kern_prototype.data.storage.FfcFiles
import com.siams.kern_prototype.data.storage.PngCheck
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** R11: импорт проверяется целиком; подтверждённые эталоны FFC не перезаписываются. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileTransferTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val store = CameraProfileStore(context, json)
    private val transfer = ProfileTransfer(context, json, store)

    private fun png(color: Int): ByteArray {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun bundle(vararg entries: ProfileEntry) =
        json.encodeToString(ProfileBundle.serializer(), ProfileBundle(entries = entries.toList()))

    private fun ffcFiles(): Set<String> = File(context.filesDir, "ffc").list()?.toSet().orEmpty()

    private fun pngOf(w: Int, h: Int, color: (x: Int, y: Int) -> Int): ByteArray {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        for (y in 0 until h) for (x in 0 until w) bmp.setPixel(x, y, color(x, y))
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun b64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** Сохранённый «подтверждённый» набор обоих профилей с эталонами. */
    private suspend fun confirmedSet(): Map<LampType, CameraProfile> {
        val dir = File(context.filesDir, "ffc").apply { mkdirs() }
        val set = LampType.entries.associateWith { lamp ->
            val f = File(dir, "confirmed-${lamp.name}-${System.nanoTime()}.png").apply { writeBytes(png(0xFF808080.toInt())) }
            CameraProfile.defaultFor(lamp).copy(iso = 222, ffcTemplatePath = f.path)
        }
        store.saveAll(set.values.toList())
        return set
    }

    /** R22: неравномерный импортируемый шаблон отклоняется до записи — весь прежний набор цел. */
    @Test
    fun unevenImportedTemplateIsRejectedAndWholeSetIsKept() = runBlocking {
        val confirmed = confirmedSet()
        val filesBefore = ffcFiles()
        val good = pngOf(8, 4) { _, _ -> 0xFF808080.toInt() }
        val uneven = pngOf(8, 4) { x, _ -> if (x == 0) 0xFF141414.toInt() else 0xFFA0A0A0.toInt() } // край в тени
        try {
            transfer.importJson(bundle(
                ProfileEntry(CameraProfile.defaultFor(LampType.DAYLIGHT).copy(iso = 999), b64(good)),
                ProfileEntry(CameraProfile.defaultFor(LampType.UF).copy(iso = 999), b64(uneven)),
            ))
            fail("ожидался отказ")
        } catch (e: IllegalArgumentException) {
            val m = e.message!!
            assertTrue(m, m.contains("«${LampType.UF.ruLabel}»") && m.contains("неравномерность") && m.contains("текущие профили не изменены"))
        }
        LampType.entries.forEach { assertEquals(confirmed[it], store.read(it)) }
        assertEquals("файлы эталонов не записывались", filesBefore, ffcFiles())
    }

    /**
     * R22: равномерный RGB с неодинаковой альфой. Для проверки через getPixels он равномерен
     * (усиление 1), но коррекция читает RGB, умноженные на альфу, — отказ до каких-либо изменений.
     */
    @Test
    fun templateWithTranslucentPixelsIsRejectedAndWholeSetIsKept() = runBlocking {
        val confirmed = confirmedSet()
        val filesBefore = ffcFiles()
        val translucent = pngOf(8, 4) { x, _ -> if (x == 0) 0x80FFFFFF.toInt() else 0xFFFFFFFF.toInt() }
        val decoded = FfcFiles.decodeTemplateBytes(translucent)!!
        assertEquals("по getPixels шаблон равномерный", List(3) { 1.0 },
            com.siams.kern_prototype.camera.FfcQuality.correctionGain(decoded))
        try {
            transfer.importJson(bundle(
                ProfileEntry(CameraProfile.defaultFor(LampType.DAYLIGHT).copy(iso = 999), b64(png(0xFF808080.toInt()))),
                ProfileEntry(CameraProfile.defaultFor(LampType.UF).copy(iso = 999), b64(translucent)),
            ))
            fail("ожидался отказ")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("полупрозрачные пиксели (4)") && e.message!!.contains("«${LampType.UF.ruLabel}»"))
        }
        LampType.entries.forEach { assertEquals(confirmed[it], store.read(it)) }
        assertEquals("файлы эталонов не записывались", filesBefore, ffcFiles())
    }

    /** Непрозрачный шаблон с альфа-каналом в формате принимается: проверяются пиксели, а не формат. */
    @Test
    fun opaqueTemplateWithAlphaFormatIsAccepted() = runBlocking {
        val opaque = pngOf(8, 4) { _, _ -> 0xFFFFFFFF.toInt() }
        assertTrue(FfcFiles.decodeTemplateBytes(opaque)!!.hasAlpha())
        assertEquals(1, transfer.importJson(bundle(ProfileEntry(CameraProfile.defaultFor(LampType.DAYLIGHT), b64(opaque)))))
    }

    /** R22: полностью нулевой канал шаблона обнулил бы канал снимков — отказ (усиление его не ловит). */
    @Test
    fun templateWithAllZeroChannelIsRejected() = runBlocking {
        val confirmed = confirmedSet()
        val noRed = pngOf(8, 4) { _, _ -> 0xFF008080.toInt() }
        try {
            transfer.importJson(bundle(ProfileEntry(CameraProfile.defaultFor(LampType.DAYLIGHT), b64(noRed))))
            fail("ожидался отказ")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("нулевой канал шаблона R"))
            assertFalse(e.message!!, e.message!!.contains("пересвет") || e.message!!.contains("сигнал"))
        }
        assertEquals(confirmed[LampType.DAYLIGHT], store.read(LampType.DAYLIGHT))
    }

    @Test
    fun corruptSecondEntryLeavesConfirmedConfigIntact() = runBlocking {
        // Подтверждённая конфигурация: дневной профиль со своим эталоном.
        val confirmedPng = png(0xFF808080.toInt())
        val confirmedFile = File(context.filesDir, "ffc/confirmed.png").apply { parentFile!!.mkdirs(); writeBytes(confirmedPng) }
        val confirmed = CameraProfile.defaultFor(LampType.DAYLIGHT).copy(iso = 200, ffcTemplatePath = confirmedFile.path)
        store.save(confirmed)
        val filesBefore = ffcFiles()

        val bad = bundle(
            ProfileEntry(CameraProfile.defaultFor(LampType.DAYLIGHT).copy(iso = 999), Base64.encodeToString(png(0xFFFFFFFF.toInt()), Base64.NO_WRAP)),
            ProfileEntry(CameraProfile.defaultFor(LampType.UF), Base64.encodeToString(byteArrayOf(1, 2, 3), Base64.NO_WRAP)),
        )
        try { transfer.importJson(bad); fail("ожидалась ошибка проверки") } catch (_: IllegalArgumentException) {}

        assertEquals(confirmed, store.read(LampType.DAYLIGHT))
        assertArrayEquals(confirmedPng, confirmedFile.readBytes())
        assertEquals(filesBefore, ffcFiles())
    }

    @Test
    fun validImportWritesNewTemplatesAndSavesAllProfiles() = runBlocking {
        val confirmedPng = png(0xFF808080.toInt())
        val confirmedFile = File(context.filesDir, "ffc/confirmed2.png").apply { parentFile!!.mkdirs(); writeBytes(confirmedPng) }
        store.save(CameraProfile.defaultFor(LampType.DAYLIGHT).copy(ffcTemplatePath = confirmedFile.path))

        val newPng = png(0xFFFFFFFF.toInt())
        val n = transfer.importJson(
            bundle(
                ProfileEntry(CameraProfile.defaultFor(LampType.DAYLIGHT).copy(iso = 300), Base64.encodeToString(newPng, Base64.NO_WRAP)),
                ProfileEntry(CameraProfile.defaultFor(LampType.UF).copy(iso = 800), null),
            ),
        )
        assertEquals(2, n)
        val day = store.read(LampType.DAYLIGHT)
        assertEquals(300, day.iso)
        assertNotEquals(confirmedFile.path, day.ffcTemplatePath)
        assertArrayEquals(newPng, File(day.ffcTemplatePath).readBytes())
        assertArrayEquals(confirmedPng, confirmedFile.readBytes()) // прежний эталон не тронут
        assertEquals(800, store.read(LampType.UF).iso)
        assertEquals("", store.read(LampType.UF).ffcTemplatePath)
        assertTrue(day.lastModifiedAt > 0)
    }

    /** Хранилище, в котором запись профилей завершается исключением — до или после сохранения. */
    private class FailingStore(context: Context, json: Json, val afterSave: Boolean) : CameraProfileStore(context, json) {
        override suspend fun saveAll(profiles: List<CameraProfile>) {
            if (afterSave) super.saveAll(profiles)
            throw CancellationException("ожидание DataStore отменено")
        }
    }

    private fun validBundle() = bundle(
        ProfileEntry(CameraProfile.defaultFor(LampType.DAYLIGHT).copy(iso = 300), Base64.encodeToString(png(0xFFFFFFFF.toInt()), Base64.NO_WRAP)),
    )

    @Test
    fun cancellationAfterSaveKeepsReferencedTemplates() = runBlocking {
        val failing = ProfileTransfer(context, json, FailingStore(context, json, afterSave = true))
        try { failing.importJson(validBundle()); fail() } catch (_: CancellationException) {}

        // Профиль записан — его эталон обязан существовать.
        val day = store.read(LampType.DAYLIGHT)
        assertEquals(300, day.iso)
        assertTrue(File(day.ffcTemplatePath).exists())
    }

    @Test
    fun cancellationBeforeSaveRemovesUnreferencedTemplates() = runBlocking {
        val before = ffcFiles()
        val failing = ProfileTransfer(context, json, FailingStore(context, json, afterSave = false))
        try { failing.importJson(validBundle()); fail() } catch (_: CancellationException) {}

        assertEquals(before, ffcFiles())
        assertNotEquals(300, store.read(LampType.DAYLIGHT).iso)
    }

    @Test
    fun pngWithReadableHeaderButCorruptPixelsIsRejected() = runBlocking {
        val good = png(0xFF336699.toInt())
        assertNull(PngCheck.problem(good))

        // 1) Порча данных IDAT без исправления CRC.
        val idat = indexOfChunk(good, "IDAT")
        val badCrc = good.copyOf().also { it[idat + 8 + 2] = (it[idat + 8 + 2].toInt() xor 0x5A).toByte() }
        // 2) Порча данных IDAT с пересчитанным CRC — ловит проверка распаковки.
        val badZlib = badCrc.copyOf().also { fixCrc(it, idat) }
        // 3) Обрезка: заголовок и размеры читаются, IEND нет.
        val truncated = good.copyOf(idat + 10)

        for ((name, bytes) in listOf("crc" to badCrc, "zlib" to badZlib, "truncated" to truncated)) {
            assertNotNull("$name: должен быть отклонён", PngCheck.problem(bytes))
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            // Старая проверка (только заголовок) такие файлы пропускала.
            if (name != "truncated") assertTrue("$name: заголовок читается", o.outWidth > 0)
            val before = ffcFiles()
            try {
                transfer.importJson(bundle(ProfileEntry(CameraProfile.defaultFor(LampType.UF), Base64.encodeToString(bytes, Base64.NO_WRAP))))
                fail("$name: импорт должен быть отклонён")
            } catch (_: IllegalArgumentException) {}
            assertEquals(before, ffcFiles())
        }
    }

    @Test
    fun oversizedPngIsRejected() {
        val big = Bitmap.createBitmap(PngCheck.MAX_SIDE + 1, 1, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { big.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        assertNotNull(PngCheck.problem(bytes))
    }

    private fun indexOfChunk(png: ByteArray, type: String): Int {
        var pos = 8
        while (pos + 8 <= png.size) {
            val len = java.nio.ByteBuffer.wrap(png, pos, 4).int
            if (String(png, pos + 4, 4, Charsets.US_ASCII) == type) return pos
            pos += 12 + len
        }
        error("нет фрагмента $type")
    }

    private fun fixCrc(png: ByteArray, chunkPos: Int) {
        val len = java.nio.ByteBuffer.wrap(png, chunkPos, 4).int
        val crc = java.util.zip.CRC32().apply { update(png, chunkPos + 4, 4 + len) }.value.toInt()
        java.nio.ByteBuffer.wrap(png, chunkPos + 8 + len, 4).putInt(crc)
    }

    // ---- п.2: экспорт ----

    @Test
    fun exportWithoutTemplateWorks() = runBlocking {
        store.save(CameraProfile.defaultFor(LampType.DAYLIGHT))
        val text = transfer.exportJson()
        assertTrue(json.decodeFromString(ProfileBundle.serializer(), text).entries.all { it.ffcPngBase64 == null })
    }

    @Test
    fun exportWithMissingTemplateFails() = runBlocking {
        store.save(CameraProfile.defaultFor(LampType.UF).copy(ffcTemplatePath = File(context.filesDir, "ffc/нет.png").path))
        try { transfer.exportJson(); fail("экспорт неполного профиля должен быть отклонён") } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("не найден"))
        }
    }

    @Test
    fun exportWithCorruptTemplateFails() = runBlocking {
        val bad = png(0xFF112233.toInt()).let { it.copyOf(it.size - 20) }
        val f = File(context.filesDir, "ffc/bad.png").apply { parentFile!!.mkdirs(); writeBytes(bad) }
        store.save(CameraProfile.defaultFor(LampType.DAYLIGHT).copy(ffcTemplatePath = f.path))
        try { transfer.exportJson(); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("повреждён"))
        }
    }

    // ---- п.1: загрузка эталона для коррекции ----

    @Test
    fun decodeTemplate() {
        val good = File(context.filesDir, "ffc/good.png").apply { parentFile!!.mkdirs(); writeBytes(png(0xFF808080.toInt())) }
        assertNotNull(FfcFiles.decodeTemplate(good.path))
        assertNull("нет файла", FfcFiles.decodeTemplate(File(context.filesDir, "ffc/none.png").path))
        val cut = File(context.filesDir, "ffc/cut.png").apply { writeBytes(good.readBytes().let { it.copyOf(it.size - 20) }) }
        assertNull("повреждён", FfcFiles.decodeTemplate(cut.path))
    }

    @Test
    fun garbageFileIsRejected() = runBlocking {
        try { transfer.importJson("{not json"); fail() } catch (_: IllegalArgumentException) {}
    }
}
