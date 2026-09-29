package com.siams.kern_prototype.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.network.S900Api
import com.siams.kern_prototype.network.dto.AnalysisSourceResponse
import com.siams.kern_prototype.network.dto.FieldOfViewDto
import com.siams.kern_prototype.network.dto.SourceImageRefDto
import com.siams.kern_prototype.network.dto.SourceImageUpdateDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Response

/** R01: файл принят ≠ метаданные доставлены; повтор и правки доводят PUT без перезаливки файла. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrillingUploaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var db: KernDatabase
    private lateinit var repo: KernRepository
    private lateinit var api: FakeS900
    private lateinit var uploader: DrillingUploader

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, KernDatabase::class.java).allowMainThreadQueries().build()
        repo = KernRepository(
            db, db.drillingDao(), db.tableDao(), db.layerDao(), FileStorage(context), PassportCodec(json), json,
        )
        api = FakeS900()
        uploader = DrillingUploader(api, repo, PassportCodec(json))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun drillingWithDaylight(): Pair<String, String> {
        val drillingId = repo.createDrilling("Д1", emptyMap())
        val (layerId, file) = repo.newLayerFile(drillingId)
        file.writeBytes(MINIMAL_JPEG)
        repo.saveCapture(drillingId, null, layerId, LampType.DAYLIGHT, file, CameraProfile.defaultFor(LampType.DAYLIGHT))
        return drillingId to layerId
    }

    @Test
    fun failedPutIsRetriedWithoutReuploadingFile() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        api.failNextPuts = 1

        try { uploader.upload(drillingId); fail("ожидался сбой PUT") } catch (_: IllegalStateException) {}
        db.layerDao().getById(layerId)!!.let {
            assertEquals("img-1", it.remoteImageId)
            assertFalse(it.metaConfirmed)
        }

        uploader.upload(drillingId)
        assertEquals(1, api.uploads)          // файл не перезалит
        assertEquals(2, api.puts.size)        // PUT доставлен при повторе
        assertTrue(db.layerDao().getById(layerId)!!.metaConfirmed)
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
    }

    @Test
    fun depthEditAfterSendIsDeliveredByPut() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        val tableId = db.layerDao().getById(layerId)!!.tableId

        repo.setLayerDepth(tableId, 1256.0, 1262.0)
        assertFalse(db.layerDao().getById(layerId)!!.metaConfirmed)
        assertEquals(SyncStatus.NOT_SENT, repo.getDrilling(drillingId)!!.syncStatus)

        uploader.upload(drillingId)
        assertEquals(1, api.uploads)
        assertEquals(1256.0, api.puts.last().attributes.kernUpperDepth!!, 0.0)
        assertEquals(1262.0, api.puts.last().attributes.kernLowerDepth!!, 0.0)
        assertTrue(db.layerDao().getById(layerId)!!.metaConfirmed)
    }

    @Test
    fun editDuringPutIsNotMarkedConfirmed() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        val tableId = db.layerDao().getById(layerId)!!.tableId
        // Правка глубины «во время» PUT: отправлено старое значение → подтверждать нельзя.
        api.onPut = { runBlocking { repo.setLayerDepth(tableId, 10.0, 12.0) } }

        try { uploader.upload(drillingId); fail("правка во время отправки не должна дать SENT") } catch (_: IllegalStateException) {}
        assertFalse(db.layerDao().getById(layerId)!!.metaConfirmed)
        assertEquals(SyncStatus.SENDING, repo.getDrilling(drillingId)!!.syncStatus)

        api.onPut = null
        uploader.upload(drillingId)
        assertEquals(10.0, api.puts.last().attributes.kernUpperDepth!!, 0.0)
        assertTrue(db.layerDao().getById(layerId)!!.metaConfirmed)
        assertEquals(1, api.uploads)
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
    }

    @Test
    fun passportEditDuringUploadIsNotSent() = runBlocking {
        val (drillingId, _) = drillingWithDaylight()
        api.onPut = { runBlocking { repo.updateDrilling(drillingId, "Д1", mapOf("field" to "Новое")) } }

        try { uploader.upload(drillingId); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("изменено во время отправки"))
        }
        assertEquals(SyncStatus.SENDING, repo.getDrilling(drillingId)!!.syncStatus)

        api.onPut = null
        uploader.upload(drillingId) // повтор воркера отправляет новый паспорт
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
        assertEquals(1, api.uploads)
    }

    @Test
    fun missingLocalFileIsNotReportedAsSent() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        java.io.File(db.layerDao().getById(layerId)!!.filePath).delete()

        try { uploader.upload(drillingId); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("не подтверждено слоёв"))
        }
        assertFalse(repo.getDrilling(drillingId)!!.syncStatus == SyncStatus.SENT)
    }

    companion object {
        /** Структурно полный JPEG (SOI … EOI) — достаточно для проверки FileStorage.isCompleteJpeg. */
        val MINIMAL_JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 1, 2, 0xFF.toByte(), 0xD9.toByte())
    }

    // ---- R05: пересъёмка во время POST (регрессия) ----

    @Test
    fun reshootDuringPostIsRefusedAndOriginalIsSent() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        val original = db.layerDao().getById(layerId)!!.filePath
        var refusal: Throwable? = null
        api.onUpload = {
            api.onUpload = null
            runBlocking {
                val (id2, f2) = repo.newLayerFile(drillingId)
                f2.writeBytes(MINIMAL_JPEG)
                refusal = runCatching {
                    repo.saveCapture(drillingId, db.layerDao().getById(layerId)!!.tableId, id2, LampType.DAYLIGHT, f2,
                        CameraProfile.defaultFor(LampType.DAYLIGHT))
                }.exceptionOrNull()
            }
        }
        uploader.upload(drillingId)

        assertTrue("пересъёмка во время отправки отклонена", refusal is IllegalStateException)
        val layer = db.layerDao().getById(layerId)!!
        assertEquals(original, layer.filePath)       // слой не заменён
        assertEquals("img-1", layer.remoteImageId)   // id — у того файла, что загружен
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
    }

    @Test
    fun deleteDuringSendingIsRefusedInRepository() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        val tableId = db.layerDao().getById(layerId)!!.tableId
        val refusals = mutableListOf<Throwable?>()
        api.onUpload = {
            api.onUpload = null
            runBlocking {
                refusals += runCatching { repo.deleteLayer(layerId) }.exceptionOrNull()
                refusals += runCatching { repo.deleteTable(tableId) }.exceptionOrNull()
            }
        }
        uploader.upload(drillingId)

        assertTrue("удаление слоя и стола отклонены: $refusals", refusals.all { it is IllegalStateException })
        assertTrue(java.io.File(db.layerDao().getById(layerId)!!.filePath).exists())
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)

        // Вне отправки удаление снова доступно (и помечает долбление для повторной отправки).
        repo.deleteLayer(layerId)
        assertNull(db.layerDao().getById(layerId))
        assertEquals(SyncStatus.NOT_SENT, repo.getDrilling(drillingId)!!.syncStatus)
    }

    @Test
    fun drillingDeleteDuringSendingIsRefused() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        var refusal: Throwable? = null
        api.onUpload = {
            api.onUpload = null
            runBlocking { refusal = runCatching { repo.deleteDrilling(drillingId) }.exceptionOrNull() }
        }
        uploader.upload(drillingId)

        assertTrue("удаление долбления во время отправки отклонено: $refusal", refusal is IllegalStateException)
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
        assertTrue(java.io.File(db.layerDao().getById(layerId)!!.filePath).exists())
    }

    @Test
    fun clearSentDeletesOnlyWhatIsStillSent() = runBlocking {
        // «Список» кандидатов получен, когда все три были SENT.
        val (edited, _) = drillingWithDaylight()
        val (resent, _) = drillingWithDaylight()
        val (sent, sentLayer) = drillingWithDaylight()
        listOf(edited, resent, sent).forEach { uploader.upload(it) }
        val sentFile = java.io.File(db.layerDao().getById(sentLayer)!!.filePath)

        // После получения списка статусы двух изменились.
        repo.updateDrilling(edited, "", mapOf("field" to "Правка"))   // → NOT_SENT
        repo.setSyncStatus(resent, SyncStatus.SENDING, null)            // снова в отправке

        assertFalse(repo.deleteDrillingIfSent(edited))
        assertFalse(repo.deleteDrillingIfSent(resent))
        assertTrue(repo.deleteDrillingIfSent(sent))

        assertTrue(repo.getDrilling(edited) != null)
        assertTrue(repo.getDrilling(resent) != null)
        assertNull(repo.getDrilling(sent))
        assertFalse("файлы удалённого долбления убраны", sentFile.exists())
    }

    @Test
    fun oldPostResponseNeverConfirmsNewFile() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        // Гонка в обход проверки репозитория: версия файла слоя сменилась, пока шёл POST.
        api.onUpload = {
            api.onUpload = null
            db.openHelper.writableDatabase.execSQL("UPDATE layer SET filePath = '/новый.jpg' WHERE id = ?", arrayOf(layerId))
        }
        try { uploader.upload(drillingId); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("переснят во время загрузки"))
        }
        db.layerDao().getById(layerId)!!.let {
            assertNull("ответ старого POST не записан в новую версию", it.remoteImageId)
            assertEquals("img-1", it.staleRemoteImageId)
            assertFalse(it.metaConfirmed)
        }
        // Повтор не должен «молча» заменить кадр и выставить SENT.
        try { uploader.upload(drillingId); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("прежний кадр"))
        }
        assertEquals(1, api.uploads)
        assertFalse(repo.getDrilling(drillingId)!!.syncStatus == SyncStatus.SENT)
    }

    @Test
    fun staleLayerBlocksSentEvenIfEverythingElseDelivered() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        db.layerDao().markStaleUpload(layerId, "img-old")
        val d = repo.getDrilling(drillingId)!!
        assertFalse(repo.markSentIfComplete(drillingId, d.editRevision))
    }

    // ---- п.2: атомарность правок ----

    @Test
    fun passportEditDoesNotOverwriteConcurrentFields() = runBlocking {
        val (drillingId, _) = drillingWithDaylight()
        repo.setSyncStatus(drillingId, SyncStatus.SENDING, null)
        repo.setDrillingRemoteId(drillingId, drillingId)
        repo.setUploadProgress(drillingId, 1, 3)
        val before = repo.getDrilling(drillingId)!!

        repo.updateDrilling(drillingId, "", mapOf("field" to "Новое"))

        val after = repo.getDrilling(drillingId)!!
        assertEquals(before.title, after.title)            // пустое название не меняет текущее
        assertEquals(SyncStatus.SENDING, after.syncStatus) // статус отправки не тронут
        assertEquals(drillingId, after.remoteId)
        assertEquals(1, after.uploadedTables)
        assertEquals(before.editRevision + 1, after.editRevision)
        assertTrue(after.passportJson.contains("Новое"))
    }

    @Test
    fun depthEditIsAtomicWithRevision() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        val tableId = db.layerDao().getById(layerId)!!.tableId
        val rev = repo.getDrilling(drillingId)!!.editRevision

        repo.onTransactionBodyEndForTest = { throw CancellationException("отмена до commit") }
        try { repo.setLayerDepth(tableId, 1.0, 2.0); fail() } catch (_: CancellationException) {}
        repo.onTransactionBodyEndForTest = null
        db.layerDao().getById(layerId)!!.let {
            assertNull("глубина без ревизии не записана", it.kernUpperDepth)
            assertTrue(it.metaConfirmed)
        }
        assertEquals(rev, repo.getDrilling(drillingId)!!.editRevision)
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)

        repo.setLayerDepth(tableId, 1.0, 2.0)
        assertEquals(rev + 1, repo.getDrilling(drillingId)!!.editRevision)
        assertEquals("remoteImageId не затёрт", "img-1", db.layerDao().getById(layerId)!!.remoteImageId)
    }

    @Test
    fun tableNumberEditIsAtomicWithRevision() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        val tableId = db.layerDao().getById(layerId)!!.tableId
        val rev = repo.getDrilling(drillingId)!!.editRevision
        repo.onTransactionBodyEndForTest = { throw CancellationException("отмена до commit") }
        try { repo.updateTableNumber(tableId, 7); fail() } catch (_: CancellationException) {}
        repo.onTransactionBodyEndForTest = null
        assertEquals(1, db.tableDao().getById(tableId)!!.number)
        assertEquals(rev, repo.getDrilling(drillingId)!!.editRevision)

        repo.updateTableNumber(tableId, 7)
        assertEquals(7, db.tableDao().getById(tableId)!!.number)
        assertEquals(rev + 1, repo.getDrilling(drillingId)!!.editRevision)
    }

    /** Подменный s900: один FoV, id кадров img-N; PUT можно уронить. */
    private class FakeS900 : S900Api {
        var uploads = 0
        val puts = mutableListOf<SourceImageUpdateDto>()
        var failNextPuts = 0
        var onPut: (() -> Unit)? = null
        var onUpload: (() -> Unit)? = null
        private val images = mutableListOf<String>()

        private fun empty(): Response<ResponseBody> = Response.success("".toResponseBody(null))

        override suspend fun createSource(body: JsonObject) = empty()
        override suspend fun updateSource(id: String, body: JsonObject) = empty()
        override suspend fun getSource(id: String): Response<AnalysisSourceResponse> = Response.success(
            AnalysisSourceResponse(
                id = id,
                fieldOfViews = listOf(FieldOfViewDto(idx = 0, sourceImages = images.map { SourceImageRefDto(id = it) })),
            ),
        )
        override suspend fun getFovSize(id: String): Response<Int> = Response.success(1)
        override suspend fun uploadImageNewFov(id: String, file: MultipartBody.Part): Response<JsonElement> = upload()
        override suspend fun uploadImageToFov(id: String, fovIdx: Int, file: MultipartBody.Part) = upload()

        private fun upload(): Response<JsonElement> {
            onUpload?.invoke()
            uploads++
            val iid = "img-$uploads"
            images += iid
            return Response.success(JsonPrimitive(iid))
        }

        override suspend fun updateImageMeta(id: String, iid: String, body: SourceImageUpdateDto): Response<ResponseBody> {
            puts += body
            if (failNextPuts > 0) {
                failNextPuts--
                return Response.error(500, "<html>Ошибка</html>".toResponseBody("text/html".toMediaType()))
            }
            onPut?.invoke()
            return empty()
        }
    }
}
