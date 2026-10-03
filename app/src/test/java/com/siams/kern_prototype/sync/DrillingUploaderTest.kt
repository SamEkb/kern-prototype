package com.siams.kern_prototype.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.KernDatabase
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.domain.model.S900Destination
import com.siams.kern_prototype.domain.model.ServerSettings
import kotlinx.serialization.json.jsonPrimitive
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.network.S900Api
import com.siams.kern_prototype.network.dto.AnalysisSourceResponse
import com.siams.kern_prototype.network.dto.FieldOfViewDto
import com.siams.kern_prototype.network.dto.SourceImageRefDto
import com.siams.kern_prototype.network.dto.SourceImageUpdateDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.Operation
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
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
import org.robolectric.annotation.GraphicsMode
import com.siams.kern_prototype.TestJpeg
import retrofit2.Response

/** R01: файл принят ≠ метаданные доставлены; повтор и правки доводят PUT без перезаливки файла. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
        settings = ServerSettingsStore(context, com.siams.kern_prototype.data.settings.TestSecrets.cipher())
        useServer("a.example")
        uploader = DrillingUploader(api, repo, PassportCodec(json), settings, FileStorage(context))
    }

    private lateinit var settings: ServerSettingsStore

    private fun useServer(host: String, login: String = "user") = runBlocking {
        settings.save(ServerSettings(host = host, port = 8080, login = login, password = "p"))
    }

    private fun destination(host: String, login: String = "user") =
        ServerSettings(host = host, port = 8080, login = login).destination()

    @After
    fun tearDown() = db.close()

    private suspend fun drillingWithDaylight(): Pair<String, String> {
        val drillingId = repo.createDrilling("Д1", emptyMap())
        val (layerId, file) = repo.newLayerFile(drillingId)
        file.writeBytes(TestJpeg.bytes())
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

        try { uploader.upload(drillingId); fail() } catch (e: UploadRejectedException) {
            assertTrue(e.message!!.contains("файл снимка отсутствует"))
        }
        assertEquals("ничего не отправлено", 0, api.uploads)
        assertFalse(repo.getDrilling(drillingId)!!.syncStatus == SyncStatus.SENT)
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
                f2.writeBytes(TestJpeg.bytes())
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
        repo.setRemoteSource(drillingId, drillingId, destination("a.example").key)
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
    // ---- R02: целостность локального архива ----

    @Test
    fun emptyOrCorruptFileRejectsBeforeAnyNetworkCall() = runBlocking {
        for ((name, bytes) in listOf("пуст" to ByteArray(0), "повреждён" to TestJpeg.bytes().copyOf(6))) {
            val (drillingId, layerId) = drillingWithDaylight()
            java.io.File(db.layerDao().getById(layerId)!!.filePath).writeBytes(bytes)
            try { uploader.upload(drillingId); fail("$name: ожидался отказ") } catch (e: UploadRejectedException) {
                assertTrue("$name: ${e.message}", e.message!!.contains("Стол №1, ${LampType.DAYLIGHT.ruLabel}: файл снимка") && e.message!!.contains(name))
            }
            assertTrue(repo.getDrilling(drillingId)!!.syncStatus != SyncStatus.SENT)
        }
        assertEquals("ни одного запроса загрузки", 0, api.uploads)
        assertTrue("и проект не создавался", api.servers.values.all { it.isEmpty() })
    }

    @Test
    fun middleCorruptionIsCaughtBySha256BeforeUpload() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        val f = java.io.File(db.layerDao().getById(layerId)!!.filePath)
        f.writeBytes(TestJpeg.corruptMiddle(f.readBytes(), "flip")) // маркеры начала/конца целы
        try { uploader.upload(drillingId); fail() } catch (e: UploadRejectedException) {
            assertTrue(e.message!!.contains("изменён или повреждён"))
        }
        assertEquals(0, api.uploads)
    }

    @Test
    fun legacyLayerWithoutShaIsCheckedByJpegValidator() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        db.openHelper.writableDatabase.execSQL("UPDATE layer SET fileSha256 = NULL WHERE id = ?", arrayOf(layerId))
        val f = java.io.File(db.layerDao().getById(layerId)!!.filePath)
        val good = f.readBytes()
        f.writeBytes(TestJpeg.corruptMiddle(good, "cut"))
        try { uploader.upload(drillingId); fail() } catch (e: UploadRejectedException) {
            assertTrue(e.message!!.contains("повреждён"))
        }
        f.writeBytes(good) // целый старый снимок без суммы отправляется
        uploader.upload(drillingId)
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
    }

    @Test
    fun deliveredLayerDoesNotNeedLocalFile() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        java.io.File(db.layerDao().getById(layerId)!!.filePath).delete() // файл уже на сервере
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 1.0, 2.0)

        uploader.upload(drillingId) // нужен только PUT метаданных
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
        assertEquals(1, api.uploads)
    }

    // ---- R03: назначение и удалённый проект ----

    @Test
    fun serverChangeTriggersFullExportToNewDestination() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        val a = destination("a.example")
        assertEquals(a.key, repo.getDrilling(drillingId)!!.remoteDestination)

        useServer("b.example")
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 5.0, 6.0) // повод отправить снова
        uploader.upload(drillingId)

        val b = destination("b.example")
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
        assertEquals(b.key, repo.getDrilling(drillingId)!!.remoteDestination)
        assertEquals("кадр выгружен на новый сервер", listOf(a.key, b.key), api.uploadDestinations)
        assertEquals(listOf(listOf("img-2")), api.projects(b)[drillingId])
        assertEquals("прежний сервер не тронут", listOf(listOf("img-1")), api.projects(a)[drillingId])
        assertEquals("img-2", db.layerDao().getById(layerId)!!.remoteImageId)
    }

    @Test
    fun existingProjectOnNewServerIsNotTrustedByOldIds() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId) // на A: img-1
        // На B уже есть проект с тем же id и ЧУЖИМ кадром, чей id совпал: «img-1».
        api.projects(destination("b.example"))[drillingId] = mutableListOf(mutableListOf("img-1"))
        useServer("b.example")
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 5.0, 6.0)

        repeat(2) { // и повторная попытка распознаёт то же самое
            try { uploader.upload(drillingId); fail("SENT по чужим id") } catch (e: UploadRejectedException) {
                assertTrue(e.message!!.contains("уже есть проект"))
            }
        }
        assertTrue(repo.getDrilling(drillingId)!!.syncStatus != SyncStatus.SENT)
        assertEquals("на B ничего не догружено вслепую", listOf(listOf("img-1")), api.projects(destination("b.example"))[drillingId])
        assertEquals(1, api.uploads)
    }

    /** Стол с парой Дневной + УФ, отправленный: на сервере один FoV [img-1, img-2]. */
    private suspend fun sentPair(): Triple<String, String, String> {
        val (drillingId, dayId) = drillingWithDaylight()
        val tableId = db.layerDao().getById(dayId)!!.tableId
        val (ufId, f) = repo.newLayerFile(drillingId)
        f.writeBytes(TestJpeg.bytes(seed = 2))
        repo.saveCapture(drillingId, tableId, ufId, LampType.UF, f, CameraProfile.defaultFor(LampType.UF))
        uploader.upload(drillingId)
        assertEquals(listOf(listOf("img-1", "img-2")), api.projects(destination("a.example"))[drillingId])
        return Triple(drillingId, tableId, db.layerDao().getByTableAndLamp(tableId, "UF")!!.id)
    }

    @Test
    fun vanishedSecondImageOfTableIsRejected() = runBlocking {
        val (drillingId, tableId, _) = sentPair()
        api.projects(destination("a.example"))[drillingId]!![0].remove("img-2") // УФ исчез, дневной на месте
        repo.setLayerDepth(tableId, 5.0, 6.0)
        try { uploader.upload(drillingId); fail("SENT без УФ-кадра на сервере") } catch (e: UploadRejectedException) {
            assertTrue(e.message!!.contains(LampType.UF.ruLabel) && e.message!!.contains("не найден"))
        }
        assertTrue(repo.getDrilling(drillingId)!!.syncStatus != SyncStatus.SENT)
        assertEquals(2, api.uploads)
    }

    @Test
    fun imagesOfTableInDifferentFovsAreRejected() = runBlocking {
        val (drillingId, tableId, _) = sentPair()
        val fovs = api.projects(destination("a.example"))[drillingId]!!
        fovs[0].remove("img-2"); fovs += mutableListOf("img-2") // УФ перемещён в другой FoV
        repo.setLayerDepth(tableId, 5.0, 6.0)
        try { uploader.upload(drillingId); fail() } catch (e: UploadRejectedException) {
            assertTrue(e.message!!.contains("разных FoV"))
        }
        assertTrue(repo.getDrilling(drillingId)!!.syncStatus != SyncStatus.SENT)
    }

    @Test
    fun loginChangeIsAnotherDestination() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        useServer("a.example", login = "other")
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 5.0, 6.0)
        uploader.upload(drillingId)
        assertEquals(destination("a.example", "other").key, repo.getDrilling(drillingId)!!.remoteDestination)
        assertEquals(2, api.uploads)
    }

    @Test
    fun projectDeletedOnServerIsExportedAgainInFull() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        api.projects(destination("a.example")).remove(drillingId) // проект удалён на сервере
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 5.0, 6.0)

        uploader.upload(drillingId)
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
        assertEquals(listOf(listOf("img-2")), api.projects(destination("a.example"))[drillingId])
        assertEquals("img-2", db.layerDao().getById(layerId)!!.remoteImageId)
    }

    @Test
    fun missingRemoteImageIsRejectedNotGuessed() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        api.projects(destination("a.example"))[drillingId]!![0].clear() // кадр удалён на сервере
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 5.0, 6.0)

        try { uploader.upload(drillingId); fail() } catch (e: UploadRejectedException) {
            assertTrue(e.message!!.contains("не найден в проекте на сервере"))
        }
        assertEquals(1, api.uploads)
        assertTrue(repo.getDrilling(drillingId)!!.syncStatus != SyncStatus.SENT)
    }

    /** Запись, отправленная до v5: назначение неизвестно. */
    private fun makeLegacy(drillingId: String) =
        db.openHelper.writableDatabase.execSQL("UPDATE drilling SET remoteDestination = NULL WHERE id = ?", arrayOf(drillingId))

    @Test
    fun unknownDestinationIsNotAdoptedAutomatically() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        makeLegacy(drillingId)
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 5.0, 6.0)
        val putsBefore = api.puts.size

        try { uploader.upload(drillingId); fail() } catch (e: UploadRejectedException) {
            assertEquals(DrillingUploader.UNKNOWN_DESTINATION, e.message)
        }
        assertNull("текущий сервер не принят как доказанный", repo.getDrilling(drillingId)!!.remoteDestination)
        assertEquals("ни одного изменяющего запроса", putsBefore, api.puts.size)
        assertEquals(1, api.uploads)
    }

    @Test
    fun legacyRecordWithCoincidingIdsOnAnotherServerIsNotTouched() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)                    // отправлялось на A
        makeLegacy(drillingId)
        // После обновления выбран B, где есть проект с тем же id и чужим кадром «img-1».
        val b = destination("b.example")
        api.projects(b)[drillingId] = mutableListOf(mutableListOf("img-1"))
        useServer("b.example")
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 5.0, 6.0)
        val putsBefore = api.puts.size

        try { uploader.upload(drillingId); fail("чужой проект был бы изменён") } catch (_: UploadRejectedException) {}
        assertEquals(putsBefore, api.puts.size)
        assertEquals(listOf(listOf("img-1")), api.projects(b)[drillingId])
        assertTrue(repo.getDrilling(drillingId)!!.syncStatus != SyncStatus.SENT)
    }

    @Test
    fun confirmedLegacyDestinationSendsNormally() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        uploader.upload(drillingId)
        makeLegacy(drillingId)
        repo.setLayerDepth(db.layerDao().getById(layerId)!!.tableId, 5.0, 6.0)

        assertTrue(repo.confirmRemoteDestination(drillingId, destination("a.example").key))
        assertFalse("повторно подтверждать нечего", repo.confirmRemoteDestination(drillingId, destination("a.example").key))
        uploader.upload(drillingId)
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
        assertEquals("без повторной выгрузки", 1, api.uploads)
    }

    // ---- Воркер: неустранимая ошибка, повтор, отмена (R02/R05) ----

    private fun worker(drillingId: String, attempt: Int = 0): UploadWorker =
        TestListenableWorkerBuilder<UploadWorker>(context)
            .setInputData(workDataOf(UploadWorker.KEY_DRILLING_ID to drillingId))
            .setRunAttemptCount(attempt)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters) =
                    UploadWorker(appContext, workerParameters, uploader, repo)
            })
            .build()

    @Test
    fun workerRejectsBrokenArchiveImmediatelyWithoutRetry() = runBlocking {
        val (drillingId, layerId) = drillingWithDaylight()
        java.io.File(db.layerDao().getById(layerId)!!.filePath).delete()
        val result = worker(drillingId).doWork()
        assertEquals(ListenableWorker.Result.failure(), result)
        repo.getDrilling(drillingId)!!.let {
            assertEquals(SyncStatus.ERROR, it.syncStatus)
            assertTrue(it.lastError!!.contains("файл снимка отсутствует"))
        }
    }

    @Test
    fun workerRetriesTransientFailure() = runBlocking {
        val (drillingId, _) = drillingWithDaylight()
        api.failNextPuts = 1
        assertEquals(ListenableWorker.Result.retry(), worker(drillingId).doWork())
        assertEquals(SyncStatus.SENDING, repo.getDrilling(drillingId)!!.syncStatus)
        assertEquals(ListenableWorker.Result.success(), worker(drillingId, attempt = 1).doWork())
        assertEquals(SyncStatus.SENT, repo.getDrilling(drillingId)!!.syncStatus)
    }

    @Test
    fun workerCancellationIsNotAnError() = runBlocking {
        val (drillingId, _) = drillingWithDaylight()
        api.onUpload = { throw CancellationException("задача остановлена системой") }
        try { worker(drillingId).doWork(); fail("отмена должна пробрасываться") } catch (_: CancellationException) {}
        repo.getDrilling(drillingId)!!.let {
            assertEquals("статус не ERROR", SyncStatus.SENDING, it.syncStatus)
            assertNull(it.lastError)
        }
    }

    @Test
    fun secondEnqueueDoesNotReplaceActiveUpload() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val wm = androidx.work.WorkManager.getInstance(context)
        sync.enqueue("d-1")
        val first = wm.getWorkInfosForUniqueWork("upload-d-1").get().single()
        sync.enqueue("d-1") // пользователь нажал «Отправить» ещё раз
        val infos = wm.getWorkInfosForUniqueWork("upload-d-1").get()
        assertEquals("идущая задача не отменена и не заменена", listOf(first.id), infos.map { it.id })
        assertFalse(infos.single().state.isFinished)
    }

    @Test
    fun reconcileCannotOverwriteSendStartedDuringIt() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val (x, _) = drillingWithDaylight()
        repo.setSyncStatus(x, SyncStatus.SENDING, null) // «зависло»: задачи нет
        val paused = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        sync.afterWorkCheckForTest = { paused.complete(Unit); resume.await() }

        val reconcile = async(Dispatchers.IO) { sync.reconcileStuck() }
        kotlinx.coroutines.withTimeout(3_000) { paused.await() } // WorkManager проверен: задачи нет
        val sending = async(Dispatchers.IO) { sync.send(listOf(x)) } // пользователь отправляет
        kotlinx.coroutines.delay(300)
        assertFalse("постановка ждёт окончания сверки", sending.isCompleted)

        resume.complete(Unit)
        kotlinx.coroutines.withTimeout(5_000) { reconcile.await(); sending.await() }
        sync.afterWorkCheckForTest = null
        assertEquals("идущая отправка не сброшена сверкой", SyncStatus.SENDING, repo.getDrilling(x)!!.syncStatus)
        assertTrue(sync.hasActiveWork(x))
    }

    /** Постановка, чьё сохранение в WorkManager выполняется только после [release]. */
    private fun delayedOperation(release: kotlinx.coroutines.CompletableDeferred<Unit>, save: () -> Unit): Operation {
        val future = CallbackToFutureAdapter.getFuture { completer ->
            Thread {
                runBlocking { release.await() }
                runCatching(save).fold({ completer.set(Operation.SUCCESS) }, { completer.setException(it) })
            }.start()
            "отложенная постановка"
        }
        return object : Operation {
            override fun getState() = androidx.lifecycle.MutableLiveData<Operation.State>(Operation.IN_PROGRESS)
            override fun getResult() = future
        }
    }

    private fun saveUploadWork(id: String) {
        androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
            "upload-$id", androidx.work.ExistingWorkPolicy.KEEP,
            androidx.work.OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(androidx.work.Constraints.Builder().setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build(),
        ).result.get()
    }

    @Test
    fun sendHoldsLockUntilWorkIsActuallySaved() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val (x, _) = drillingWithDaylight()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        sync.enqueueForTest = { id -> delayedOperation(release) { saveUploadWork(id) } }

        val sending = async(Dispatchers.IO) { sync.send(listOf(x)) }
        kotlinx.coroutines.delay(200)
        assertFalse("задача ещё не сохранена в WorkManager", sync.hasActiveWork(x))
        val reconcile = async(Dispatchers.IO) { sync.reconcileStuck() } // сверка во время постановки
        kotlinx.coroutines.delay(300)
        assertFalse("send ждёт сохранения задачи", sending.isCompleted)
        assertFalse("сверка ждёт замка", reconcile.isCompleted)

        release.complete(Unit)
        kotlinx.coroutines.withTimeout(5_000) { sending.await(); reconcile.await() }
        sync.enqueueForTest = null
        assertEquals("сверка не сбросила отправку", SyncStatus.SENDING, repo.getDrilling(x)!!.syncStatus)
        assertTrue(sync.hasActiveWork(x))
    }

    @Test
    fun cancelledSendStillCompletesStartedEnqueueUnderLock() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val (x, _) = drillingWithDaylight()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        sync.enqueueForTest = { id -> delayedOperation(release) { saveUploadWork(id) } }

        val sending = async(Dispatchers.IO) { sync.send(listOf(x)) }
        kotlinx.coroutines.delay(200)                 // постановка началась, задача не сохранена
        sending.cancel()                              // вызывающего отменили (например, закрыт экран)
        val reconcile = async(Dispatchers.IO) { sync.reconcileStuck() }
        kotlinx.coroutines.delay(300)
        assertFalse("исход постановки неизвестен — сверка ждёт", reconcile.isCompleted)
        assertEquals(SyncStatus.SENDING, repo.getDrilling(x)!!.syncStatus)

        release.complete(Unit)                        // WorkManager сохранил задачу
        kotlinx.coroutines.withTimeout(5_000) { reconcile.await() }
        try { sending.await(); fail("отмена должна передаваться вызывающему") } catch (_: kotlinx.coroutines.CancellationException) {}
        sync.enqueueForTest = null
        assertEquals("сверка не разблокировала долбление", SyncStatus.SENDING, repo.getDrilling(x)!!.syncStatus)
        assertTrue(sync.hasActiveWork(x))
    }

    @Test
    fun cancelledSendWithFailedEnqueueEndsInError() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val (x, _) = drillingWithDaylight()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        sync.enqueueForTest = { delayedOperation(release) { error("БД WorkManager недоступна") } }

        val sending = async(Dispatchers.IO) { sync.send(listOf(x)) }
        kotlinx.coroutines.delay(200)
        sending.cancel()
        val reconcile = async(Dispatchers.IO) { sync.reconcileStuck() }
        kotlinx.coroutines.delay(300)
        assertFalse(reconcile.isCompleted)

        release.complete(Unit)                        // постановка завершилась неудачей
        kotlinx.coroutines.withTimeout(5_000) { reconcile.await() }
        runCatching { sending.await() }
        sync.enqueueForTest = null
        repo.getDrilling(x)!!.let {
            assertEquals(SyncStatus.ERROR, it.syncStatus)
            assertTrue(it.lastError!!.contains("Не удалось поставить отправку в очередь"))
        }
    }

    @Test
    fun cancelledSendDoesNotStartRemainingDrillings() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val (first, _) = drillingWithDaylight()
        val (second, _) = drillingWithDaylight()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        sync.enqueueForTest = { id -> delayedOperation(release) { saveUploadWork(id) } }

        val sending = async(Dispatchers.IO) { sync.send(listOf(first, second)) }
        kotlinx.coroutines.delay(200)
        sending.cancel()
        release.complete(Unit)
        runCatching { kotlinx.coroutines.withTimeout(5_000) { sending.await() } }
        sync.enqueueForTest = null
        assertTrue(sync.hasActiveWork(first))
        assertFalse(sync.hasActiveWork(second))
        assertEquals(SyncStatus.NOT_SENT, repo.getDrilling(second)!!.syncStatus)
    }

    @Test
    fun failedEnqueueLeavesSendingState() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val (x, _) = drillingWithDaylight()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>().apply { complete(Unit) }
        sync.enqueueForTest = { delayedOperation(release) { error("БД WorkManager недоступна") } }
        sync.send(listOf(x))
        sync.enqueueForTest = null
        repo.getDrilling(x)!!.let {
            assertEquals("не остаётся SENDING без задачи", SyncStatus.ERROR, it.syncStatus)
            assertTrue(it.lastError!!.contains("Не удалось поставить отправку в очередь"))
        }
        repo.deleteDrilling(x) // блокировки SENDING нет
    }

    @Test
    fun reconcileDoesNotOverwriteFinishedUpload() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val (x, _) = drillingWithDaylight()
        repo.setSyncStatus(x, SyncStatus.SENDING, null)
        // Отправка успела завершиться между чтением списка и записью статуса.
        sync.afterWorkCheckForTest = { repo.setSyncStatus(x, SyncStatus.SENT, null) }
        sync.reconcileStuck()
        sync.afterWorkCheckForTest = null
        assertEquals(SyncStatus.SENT, repo.getDrilling(x)!!.syncStatus)
    }

    @Test
    fun stuckSendingWithoutWorkIsReleased() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().build())
        val sync = SyncManager(context, repo)
        val (stuck, _) = drillingWithDaylight()
        val (queued, _) = drillingWithDaylight()
        repo.setSyncStatus(stuck, SyncStatus.SENDING, null)   // задачи нет (потеряна)
        repo.setSyncStatus(queued, SyncStatus.SENDING, null)
        sync.enqueue(queued)                                   // ждёт сети — активна

        sync.reconcileStuck()
        assertEquals(SyncStatus.ERROR, repo.getDrilling(stuck)!!.syncStatus)
        assertEquals(SyncStatus.SENDING, repo.getDrilling(queued)!!.syncStatus)
        // Снятая блокировка: кадр снова можно удалить.
        repo.deleteDrilling(stuck)
        assertNull(repo.getDrilling(stuck))
    }

    /**
     * Подменный s900 с несколькими серверами: у каждого назначения свои проекты, FoV и кадры.
     * id кадров img-N уникальны глобально; PUT можно уронить; хуки — для гонок.
     */
    private class FakeS900 : S900Api {
        var uploads = 0
        val uploadDestinations = mutableListOf<String>()
        val puts = mutableListOf<SourceImageUpdateDto>()
        var failNextPuts = 0
        var onPut: (() -> Unit)? = null
        var onUpload: (() -> Unit)? = null

        /** назначение → id проекта → FoV → id кадров. */
        val servers = mutableMapOf<String, MutableMap<String, MutableList<MutableList<String>>>>()
        fun projects(d: S900Destination) = servers.getOrPut(d.key) { mutableMapOf() }

        private fun empty(): Response<ResponseBody> = Response.success("".toResponseBody(null))
        private fun <T> notFound(): Response<T> = Response.error(404, "<html>Not found</html>".toResponseBody("text/html".toMediaType()))

        override suspend fun createSource(destination: S900Destination, body: JsonObject): Response<ResponseBody> {
            projects(destination).getOrPut(body["id"]!!.jsonPrimitive.content) { mutableListOf() }
            return empty()
        }
        override suspend fun updateSource(destination: S900Destination, id: String, body: JsonObject): Response<ResponseBody> =
            if (id in projects(destination)) empty() else notFound()
        override suspend fun getSource(destination: S900Destination, id: String): Response<AnalysisSourceResponse> {
            val fovs = projects(destination)[id] ?: return notFound()
            return Response.success(
                AnalysisSourceResponse(
                    id = id,
                    fieldOfViews = fovs.mapIndexed { i, imgs -> FieldOfViewDto(idx = i, sourceImages = imgs.map { SourceImageRefDto(id = it) }) },
                ),
            )
        }
        override suspend fun getFovSize(destination: S900Destination, id: String): Response<Int> =
            projects(destination)[id]?.let { Response.success(it.size) } ?: notFound()
        override suspend fun uploadImageNewFov(destination: S900Destination, id: String, file: MultipartBody.Part): Response<JsonElement> =
            upload(destination, id, null)
        override suspend fun uploadImageToFov(destination: S900Destination, id: String, fovIdx: Int, file: MultipartBody.Part) =
            upload(destination, id, fovIdx)

        private fun upload(d: S900Destination, id: String, fovIdx: Int?): Response<JsonElement> {
            onUpload?.invoke()
            val fovs = projects(d)[id] ?: return notFound()
            uploads++
            uploadDestinations += d.key
            val iid = "img-$uploads"
            if (fovIdx == null) fovs += mutableListOf(iid) else fovs[fovIdx] += iid
            return Response.success(JsonPrimitive(iid))
        }

        override suspend fun updateImageMeta(
            destination: S900Destination, id: String, iid: String, body: SourceImageUpdateDto,
        ): Response<ResponseBody> {
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
