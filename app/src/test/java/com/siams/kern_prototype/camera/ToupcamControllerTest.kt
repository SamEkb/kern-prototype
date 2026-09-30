package com.siams.kern_prototype.camera

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.settings.CameraProfileStore
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Настоящий [ToupcamController] с подменными устройствами. Кроме сценариев каждого теста, после
 * любого теста проверяются инварианты единственного нативного хэндла ([FakeCameraRig.violations]):
 * нет вызовов после закрытия, нет параллельных вызовов, не больше одного открытого устройства.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToupcamControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val rig = FakeCameraRig()
    private val controllers = mutableListOf<ToupcamController>()

    /** Устройства контроллера именуются «<prefix><n>»: подключения разных контроллеров различимы. */
    private fun controller(prefix: String = ""): ToupcamController {
        var n = 0
        return ToupcamController(
            context, CameraProfileStore(context, json),
            { FakeToupcamDevice(rig, prefix + (++n)).also { rig.devices += it } },
            snapTimeoutMs = 300, shutdownWaitMs = 5_000,
        ).also { controllers += it }
    }

    @After
    fun tearDown() {
        listOf(rig.accessGate).forEach { it?.complete(Unit) }
        listOf(rig.openGate, rig.snapGate, rig.setterGate, rig.pullGate).forEach { it?.countDown() }
        controllers.forEach { it.release() }
        // Все подключения закрыты → владение возвращено (иначе следующий тест не подключится).
        val deadline = System.currentTimeMillis() + 5_000
        while (rig.openDevice != null && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertEquals("камера осталась открытой после release", null, rig.openDevice)
        assertEquals("нарушения единственного хэндла", emptyList<String>(), rig.violations.toList())
    }

    /** Ожидание с пределом: зависание (например, в мутационной проверке) — явное падение теста. */
    private suspend fun <T> Deferred<T>.awaitWithin(ms: Long = 5_000): T = withTimeout(ms) { await() }

    private fun target() = File(context.filesDir, "shot-${System.nanoTime()}.jpg")

    private fun waitCount(entry: String, n: Int, ms: Long = 3000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (rig.snapshot().count { it == entry } >= n) return true; Thread.sleep(5) }
        return false
    }

    @Test
    fun normalCaptureWritesFile() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        val f = target()
        c.capture(f, LampType.DAYLIGHT)
        assertTrue(f.length() > 0)
    }

    // ---- п.1: release() во время подключения -------------------------------------------

    @Test
    fun releaseWhileWaitingForUsbAccessNeverOpensCamera() = runBlocking {
        rig.accessGate = CompletableDeferred()
        val c = controller("A")
        val opening = async(Dispatchers.IO) { runCatching { c.open(LampType.DAYLIGHT) } }
        assertTrue(rig.waitFor("accessA1"))

        c.release()                                  // пользователь ушёл, пока висит диалог доступа
        val result = opening.awaitWithin()
        assertTrue("подключение должно завершиться ошибкой", result.isFailure)
        rig.accessGate!!.complete(Unit)              // запоздалое согласие ничего не открывает
        Thread.sleep(100)
        assertFalse("камера открыта после release", rig.has("openA1"))
        assertFalse(c.state.value is CameraControllerState.Ready)

        val b = controller("B")                      // владение возвращено
        b.open(LampType.DAYLIGHT)
        assertTrue(rig.has("openB1"))
    }

    @Test
    fun releaseDuringNativeOpenClosesThatConnection() = runBlocking {
        rig.openGate = CountDownLatch(1)
        val c = controller("A")
        val opening = async(Dispatchers.IO) { runCatching { c.open(LampType.DAYLIGHT) } }
        assertTrue(rig.waitFor("openStartA1"))

        c.release()                                  // нативное открытие ещё идёт
        Thread.sleep(100)
        assertFalse("закрытие не должно вклиниться в открытие", rig.has("closeA1"))
        rig.openGate!!.countDown()

        assertTrue(opening.awaitWithin().isFailure)
        assertTrue("открытая после release камера закрыта", rig.waitFor("closeA1"))
        assertTrue(rig.indexOf("openA1") < rig.indexOf("closeA1"))
        assertFalse(c.state.value is CameraControllerState.Ready)

        val b = controller("B")
        b.open(LampType.DAYLIGHT)
        assertTrue(rig.indexOf("closeA1") < rig.indexOf("openB1"))
    }

    // ---- п.2: единственный владелец SDK на процесс --------------------------------------

    @Test
    fun twoControllersOpenConcurrently_secondWaitsAndSurvivesFirstRelease() = runBlocking {
        val a = controller("A")
        val b = controller("B")
        val openA = async(Dispatchers.IO) { a.open(LampType.DAYLIGHT) }
        val openB = async(Dispatchers.IO) { b.open(LampType.DAYLIGHT) }
        Thread.sleep(300)
        val firstOpened = listOf("openA1", "openB1").filter { rig.has(it) }
        assertEquals("одновременно открыта только одна камера: ${rig.snapshot()}", 1, firstOpened.size)
        val (first, second) = if (firstOpened.single() == "openA1") a to b else b to a
        val secondOpen = if (first === a) "openB1" else "openA1"

        first.release()
        (if (first === a) openB else openA).awaitWithin()
        (if (first === a) openA else openB).awaitWithin()
        assertTrue(rig.has(secondOpen))

        // Освобождение первого не задело второе подключение.
        assertTrue(second.state.value is CameraControllerState.Ready)
        val f = target()
        second.capture(f, LampType.DAYLIGHT)
        assertTrue(f.length() > 0)
    }

    // ---- п.3: Snap / настройки / AE против закрытия --------------------------------------

    @Test
    fun releaseDuringSnapWaitsForSnapToFinish() = runBlocking {
        val c = controller("A")
        c.open(LampType.DAYLIGHT)
        rig.snapGate = CountDownLatch(1)
        val shot = async(Dispatchers.IO) { runCatching { c.capture(target(), LampType.DAYLIGHT) } }
        assertTrue(rig.waitFor("snapStartA1"))

        c.release()
        Thread.sleep(150)
        assertFalse("камера закрыта во время Snap", rig.has("closeA1"))
        rig.snapGate!!.countDown()
        assertTrue(rig.waitFor("closeA1"))
        assertTrue(rig.indexOf("snapA1") < rig.indexOf("closeA1"))
        assertTrue("снимок после release не успешен", shot.awaitWithin().isFailure)
    }

    @Test
    fun releaseDuringProfileSetterWaitsForSetter() = runBlocking {
        val c = controller("A")
        c.open(LampType.DAYLIGHT)
        rig.setterGate = CountDownLatch(1)
        val applying = async(Dispatchers.IO) { c.applyProfile(CameraProfile.defaultFor(LampType.UF)) }
        // Первый setExpo — при открытии камеры; ждём второй — из applyProfile.
        assertTrue(waitCount("setExpoStartA1", 2))

        c.release()
        Thread.sleep(150)
        assertFalse("камера закрыта во время настройки", rig.has("closeA1"))
        rig.setterGate!!.countDown()
        applying.awaitWithin()
        assertTrue(rig.waitFor("closeA1"))
        assertTrue(rig.snapshot().lastIndexOf("setExpoA1") < rig.indexOf("closeA1"))

        // После освобождения настройки не доходят до хэндла.
        c.applyProfile(CameraProfile.defaultFor(LampType.DAYLIGHT))
    }

    @Test
    fun releaseBetweenAutoExposureStepsStopsSdkCalls() = runBlocking {
        val c = controller("A")
        c.open(LampType.DAYLIGHT)
        val ae = async(Dispatchers.IO) { runCatching { c.autoExposure(CameraProfile.defaultFor(LampType.DAYLIGHT)) } }
        assertTrue(rig.waitFor("ae1A1"))            // авто-AE включена, идёт ожидание сходимости
        c.release()
        val result = ae.awaitWithin()
        assertTrue(result.isFailure)
        // ae0 (выключение авто-AE) пишется и при открытии; после ae1 его быть не должно.
        assertTrue("после release к SDK не обращаются: ${rig.snapshot()}", rig.snapshot().lastIndexOf("ae0A1") < rig.indexOf("ae1A1"))
    }

    // ---- R10: согласованность профиля и снимка -------------------------------------------

    private fun expoBefore(entry: String): String? =
        rig.snapshot().let { l -> l.subList(0, l.indexOf(entry)).lastOrNull { it.startsWith("expo") } }

    @Test
    fun captureWithOtherLampAppliesItsProfileBeforeSnap() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        val uf = CameraProfile.defaultFor(LampType.UF)
        val profile = c.capture(target(), LampType.UF)
        assertEquals(LampType.UF, profile.lampType)
        assertEquals("снимок — с выдержкой УФ", "expo1=${uf.exposureTimeNs / 1000}", expoBefore("snap1"))
        assertEquals(LampType.UF, c.activeProfile.lampType)
    }

    @Test
    fun captureRightAfterSwitchWaitsForTheSwitch() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.setterGate = CountDownLatch(1)          // переключение на УФ применяется медленно
        val switching = async(Dispatchers.IO) { c.switchLamp(LampType.UF) }
        assertTrue(waitCount("setExpoStart1", 2))
        val shot = async(Dispatchers.IO) { c.capture(target(), LampType.UF) }
        Thread.sleep(200)
        assertFalse("Snap посреди смены профиля", rig.has("snap1"))
        rig.setterGate!!.countDown()
        switching.awaitWithin()
        val profile = shot.awaitWithin()
        assertEquals(LampType.UF, profile.lampType)
        assertEquals("expo1=${CameraProfile.defaultFor(LampType.UF).exposureTimeNs / 1000}", expoBefore("snap1"))
    }

    @Test
    fun lampSwitchCannotSlipBetweenApplyAndSnap() = runBlocking {
        // Кадры редкие → снимок после применения УФ ждёт их ~0.6 с: это окно для вклинивания.
        rig.frameIntervalMs = 300
        val c = controller()
        c.open(LampType.DAYLIGHT)
        val ufUs = CameraProfile.defaultFor(LampType.UF).exposureTimeNs / 1000
        val shot = async(Dispatchers.IO) { c.capture(target(), LampType.UF) }
        assertTrue(rig.waitFor("expo1=$ufUs"))       // профиль УФ применён, Snap ещё не сделан
        val switching = async(Dispatchers.IO) { c.switchLamp(LampType.DAYLIGHT) }
        val profile = withTimeout(10_000) { shot.await() }
        switching.awaitWithin()
        assertEquals(LampType.UF, profile.lampType)
        assertEquals("Snap — с параметрами УФ, а не вклинившегося дневного", "expo1=$ufUs", expoBefore("snap1"))
    }

    private val dayUs = CameraProfile.defaultFor(LampType.DAYLIGHT).exposureTimeNs / 1000
    private val ufUs = CameraProfile.defaultFor(LampType.UF).exposureTimeNs / 1000

    @Test
    fun cancelledSwitchLeavesProfileUnknownAndItIsReappliedBeforeSnap() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.setterGate = CountDownLatch(1)
        val switching = async(Dispatchers.IO) { c.switchLamp(LampType.UF) }
        assertTrue(waitCount("setExpoStart1", 2))
        switching.cancel()                    // отмена, пока сеттер УФ выполняется
        rig.setterGate!!.countDown()          // сеттер всё же завершился: камера фактически на УФ
        runCatching { switching.awaitWithin() }
        rig.setterGate = null

        c.capture(target(), LampType.DAYLIGHT)
        assertEquals("перед дневным снимком профиль применён заново", "expo1=$dayUs", expoBefore("snap1"))
    }

    @Test
    fun cancelledCaptureApplyAlsoInvalidatesProfile() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.setterGate = CountDownLatch(1)
        val shot = async(Dispatchers.IO) { c.capture(target(), LampType.UF) } // доприменение УФ
        assertTrue(waitCount("setExpoStart1", 2))
        shot.cancel()
        rig.setterGate!!.countDown()
        runCatching { shot.awaitWithin() }
        rig.setterGate = null

        c.capture(target(), LampType.DAYLIGHT)
        assertEquals("expo1=$dayUs", expoBefore("snap1"))
    }

    private fun framesBetween(from: String, to: String): Int {
        val log = rig.snapshot()
        val a = log.lastIndexOf(from)
        val b = log.indexOf(to)
        return log.subList(a, b).count { it == "frame1" }
    }

    @Test
    fun snapWaitsForFramesEvenAfterRegularSwitch() = runBlocking {
        rig.logFrames = true
        rig.frameIntervalMs = 100
        val c = controller()
        c.open(LampType.DAYLIGHT)
        c.switchLamp(LampType.UF)             // профиль уже совпадает с лампой снимка
        c.capture(target(), LampType.UF)
        assertTrue("кадры после применения до Snap: ${rig.snapshot()}", framesBetween("expo1=$ufUs", "snap1") >= 2)
    }

    @Test
    fun framesQueuedBeforeApplyDoNotCountAsSettled() = runBlocking {
        rig.logFrames = true
        rig.frameIntervalMs = 1
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.setterGate = CountDownLatch(1)
        val first = async(Dispatchers.IO) { c.applyProfile(CameraProfile.defaultFor(LampType.DAYLIGHT)) }
        assertTrue(waitCount("setExpoStart1", 2))  // очередь подключения занята сеттером
        Thread.sleep(300)                          // кадры копятся в очереди — они ДО применения УФ
        rig.frameIntervalMs = 200                  // после применения кадры редкие
        val shot = async(Dispatchers.IO) { c.capture(target(), LampType.UF) }
        rig.setterGate!!.countDown()
        rig.setterGate = null
        first.awaitWithin()
        shot.awaitWithin()
        assertTrue("кадры, пришедшие до применения, не засчитаны: ${framesBetween("expo1=$ufUs", "snap1")}",
            framesBetween("expo1=$ufUs", "snap1") >= 2)
    }

    @Test
    fun framesArrivingDuringSettersDoNotCountAsSettled() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.previewFrames = false                  // дальше кадры только вручную
        rig.setterGate = CountDownLatch(1)         // 1) сеттер применяемого УФ задержан
        val shot = async(Dispatchers.IO) { runCatching { c.capture(target(), LampType.UF) } }
        assertTrue(waitCount("setExpoStart1", 2))
        val device = rig.devices[0]
        repeat(2) { device.listener!!(ToupcamController.EVENT_IMAGE) } // 2) два кадра во время сеттера
        rig.setterGate!!.countDown()               // 3) кадров больше нет, сеттер освобождён
        rig.setterGate = null
        val result = withTimeout(10_000) { shot.await() } // 4) УФ-снимок
        assertTrue("ожидался отказ по таймауту", result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("не подтвердила новые параметры"))
        assertFalse("Snap по кадрам, пришедшим до завершения применения", rig.has("snap1"))
    }

    @Test
    fun missingPreviewFramesFailCaptureInsteadOfSilentSnap() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.previewFrames = false             // камера не подтверждает новые параметры
        try { c.capture(target(), LampType.UF); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("не подтвердила новые параметры"))
        }
        assertFalse(rig.has("snap1"))
    }

    @Test
    fun cancelledSettleWaitIsNotSkippedByNextCapture() = runBlocking {
        rig.logFrames = true
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.previewFrames = false
        val shot = async(Dispatchers.IO) { runCatching { c.capture(target(), LampType.UF) } }
        assertTrue(rig.waitFor("expo1=$ufUs"))  // УФ применён, ожидание кадров идёт
        Thread.sleep(100)
        shot.cancel()
        runCatching { shot.awaitWithin() }

        rig.frameIntervalMs = 100
        rig.previewFrames = true
        c.capture(target(), LampType.UF)       // профиль тот же — но ожидание не пропускается
        assertTrue(framesBetween("expo1=$ufUs", "snap1") >= 2)
    }

    @Test
    fun appliedProfileIsReportedOnlyAfterSdkSetters() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.setterGate = CountDownLatch(1)
        val applying = async(Dispatchers.IO) { c.applyProfile(CameraProfile.defaultFor(LampType.UF)) }
        assertTrue(waitCount("setExpoStart1", 2))
        assertEquals("сеттеры ещё не выполнены", LampType.DAYLIGHT, c.activeProfile.lampType)
        rig.setterGate!!.countDown()
        applying.awaitWithin()
        assertEquals(LampType.UF, c.activeProfile.lampType)
    }

    @Test
    fun cancelledAutoExposureRestoresManualMode() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        val ae = async(Dispatchers.IO) { c.autoExposure(CameraProfile.defaultFor(LampType.DAYLIGHT)) }
        assertTrue(rig.waitFor("ae11"))                // авто-AE включена
        ae.cancel()
        runCatching { ae.awaitWithin() }
        assertTrue("ручной режим возвращён: ${rig.snapshot()}", rig.snapshot().lastIndexOf("ae01") > rig.indexOf("ae11"))
    }

    // ---- R16: восстановление и поверхность превью -----------------------------------------

    @Test
    fun disconnectedCameraIsRecoveredOnRequest() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.devices[0].listener!!(ToupcamController.EVENT_DISCONNECTED)
        waitUntilState { it is CameraControllerState.Error && it.canRetry }
        c.recover()
        assertTrue(rig.has("open2"))
        assertTrue(c.state.value is CameraControllerState.Ready)
        val f = target()
        c.capture(f, LampType.DAYLIGHT)
        assertTrue(f.length() > 0)
    }

    @Test
    fun failedFirstOpenCanBeRecovered() = runBlocking {
        rig.failAccess += "1"
        val c = controller()
        try { c.open(LampType.DAYLIGHT); fail() } catch (_: IllegalStateException) {}
        assertTrue(c.state.value is CameraControllerState.Error)
        c.recover()
        assertTrue(rig.has("open2"))
        assertTrue(c.state.value is CameraControllerState.Ready)
    }

    @Test
    fun recoverAfterReleaseIsRefused() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        c.release()
        try { c.recover(); fail("освобождённый контроллер не подключается") } catch (_: IllegalStateException) {}
        assertFalse(rig.has("access2"))
    }

    private fun newSurface() = android.view.Surface(android.graphics.SurfaceTexture(0))

    /** detachSurface блокирующий — вызываем в отдельном потоке, чтобы видеть, когда он вернётся. */
    private fun detachAsync(c: ToupcamController, s: android.view.Surface) =
        CompletableDeferred<Unit>().also { done -> kotlin.concurrent.thread { c.detachSurface(s); rig.log += "detached"; done.complete(Unit) } }

    @Test
    fun detachSurfaceWaitsForRunningPreviewDraw() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        val surface = newSurface()
        rig.pullImageGate = CountDownLatch(1)
        c.startSession(surface)
        assertTrue(rig.waitFor("pullImageStart1"))   // кадр рисуется на эту поверхность
        val detaching = detachAsync(c, surface)
        Thread.sleep(200)
        assertFalse("поверхность отпущена посреди отрисовки", detaching.isCompleted)
        rig.pullImageGate!!.countDown()
        rig.pullImageGate = null
        detaching.awaitWithin()
        assertTrue(rig.indexOf("pullImageEnd1") < rig.indexOf("detached"))
        val before = rig.snapshot().count { it == "pullImage1" }
        Thread.sleep(200)                            // кадры идут дальше
        assertEquals("после отключения на поверхность не рисуют", before, rig.snapshot().count { it == "pullImage1" })
        surface.release()
    }

    @Test
    fun detachAfterReleaseStillWaitsForRunningDraw() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        val surface = newSurface()
        rig.pullImageGate = CountDownLatch(1)
        c.startSession(surface)
        assertTrue(rig.waitFor("pullImageStart1"))
        c.release()                                  // подключение закрывается, отрисовка ещё идёт
        val detaching = detachAsync(c, surface)      // экран уничтожает поверхность
        Thread.sleep(200)
        assertFalse("отсоединение не ждёт отрисовку закрывающегося подключения", detaching.isCompleted)
        rig.pullImageGate!!.countDown()
        rig.pullImageGate = null
        detaching.awaitWithin()
        assertTrue(rig.indexOf("pullImageEnd1") < rig.indexOf("detached"))
        surface.release()
    }

    @Test
    fun lateDetachOfOldSurfaceKeepsNewOne() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        val a = newSurface()
        val b = newSurface()
        rig.pullImageGate = CountDownLatch(1)
        c.startSession(a)
        assertTrue(rig.waitFor("pullImageStart1"))  // рисуем на A
        val detachingA = detachAsync(c, a)          // уничтожение A задержано отрисовкой
        val attachingB = async(Dispatchers.IO) { c.startSession(b) }
        Thread.sleep(100)
        rig.pullImageGate!!.countDown()
        rig.pullImageGate = null
        detachingA.awaitWithin()
        attachingB.awaitWithin()
        assertTrue("превью осталось на B", c.currentPreviewSurface === b)
        c.detachSurface(a)                          // ещё одно запоздалое отсоединение A
        assertTrue(c.currentPreviewSurface === b)
        a.release(); b.release()
    }

    private fun waitUntilState(cond: (CameraControllerState) -> Boolean) = runBlocking {
        withTimeout(3_000) { while (!cond(controllers.last().state.value)) kotlinx.coroutines.delay(10) }
    }

    // ---- п.3–5 предыдущего ревью ---------------------------------------------------------

    /**
     * Закрытие камеры и новое подключение (другим экраном) ждут задержанный обработчик;
     * освобождённый контроллер не переподключается, даже если его снимок завершился таймаутом.
     */
    @Test
    fun releaseAndReopenWaitForRunningHandler() = runBlocking {
        val c = controller("A")
        c.open(LampType.DAYLIGHT)
        rig.pullGate = CountDownLatch(1)
        val shot = async(Dispatchers.IO) { runCatching { c.capture(target(), LampType.DAYLIGHT) } }
        assertTrue(rig.waitFor("pullStartA1"))

        c.release()                                   // обработчик ещё читает кадр
        val c2 = controller("B")
        val reopen = async(Dispatchers.IO) { c2.open(LampType.DAYLIGHT) }
        Thread.sleep(500)                             // дольше таймаута снимка первого экрана
        assertFalse("камера закрыта под читающим обработчиком", rig.has("closeA1"))
        assertFalse("новое подключение начато до завершения обработчика", rig.has("openB1"))

        rig.pullGate!!.countDown()
        reopen.awaitWithin()
        shot.awaitWithin()
        Thread.sleep(200)
        assertTrue(rig.indexOf("pullEndA1") < rig.indexOf("closeA1"))
        assertTrue(rig.indexOf("closeA1") < rig.indexOf("openB1"))
        assertFalse("освобождённый контроллер переподключился: ${rig.snapshot()}", rig.has("accessA2"))
        assertTrue(c2.state.value is CameraControllerState.Ready)
    }

    /** Таймаут → переподключение не удалось → следующий снимок снова подключается. */
    @Test
    fun failedReconnectIsRetriedByNextCapture() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.autoStill = false                 // камера «молчит» → таймаут
        rig.failAccess += "2"                 // и переподключение не удаётся
        try { c.capture(target(), LampType.DAYLIGHT); fail() } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("не вернула снимок"))
        }
        assertTrue(rig.has("access2"))
        val st = c.state.value
        assertTrue("ошибка с возможностью повтора: $st", st is CameraControllerState.Error && st.canRetry)

        rig.autoStill = true
        val f = target()
        c.capture(f, LampType.DAYLIGHT)       // повтор: сначала переподключение
        assertTrue(rig.indexOf("open3") < rig.indexOf("snap3"))
        assertTrue(f.length() > 0)
        assertTrue(c.state.value is CameraControllerState.Ready)
    }

    /** То же для съёмки эталона FFC. */
    @Test
    fun failedReconnectIsRetriedByFfcReference() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.autoStill = false
        rig.failAccess += "2"
        try { c.captureFfcReference(LampType.DAYLIGHT); fail() } catch (_: IllegalStateException) {}

        rig.autoStill = true
        val path = c.captureFfcReference(LampType.DAYLIGHT)
        assertTrue(rig.indexOf("open3") < rig.indexOf("snap3"))
        assertTrue(File(path).exists())
    }

    /** Отмена, когда событие уже получено и кадр читается, требует переподключения. */
    @Test
    fun cancellationDuringReadForcesReconnectBeforeNextSnap() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        val gate = CountDownLatch(1)
        rig.pullGate = gate
        val job = async(Dispatchers.IO) { c.capture(target(), LampType.DAYLIGHT) }
        assertTrue(rig.waitFor("pullStart1")) // событие получено, кадр читается
        job.cancel()
        runCatching { job.awaitWithin() }

        rig.pullGate = null // новые чтения не задерживаются; первое всё ещё ждёт gate
        val next = async(Dispatchers.IO) { c.capture(target(), LampType.DAYLIGHT) }
        Thread.sleep(300)
        assertEquals("второй Snap на старом подключении недопустим", 1, rig.snapshot().count { it == "snap1" })
        assertFalse("переподключение до завершения чтения", rig.has("open2"))

        gate.countDown()
        next.awaitWithin()
        assertTrue(rig.indexOf("pullEnd1") < rig.indexOf("close1"))
        assertTrue(rig.indexOf("close1") < rig.indexOf("open2"))
        assertTrue(rig.indexOf("open2") < rig.indexOf("snap2"))
    }

    /** Позднее событие старого Snap (после таймаута) не завершает новый снимок. */
    @Test
    fun lateEventOfTimedOutSnapDoesNotCompleteNewSnap() = runBlocking {
        val c = controller()
        c.open(LampType.DAYLIGHT)
        rig.autoStill = false
        try { c.capture(target(), LampType.DAYLIGHT); fail() } catch (_: IllegalStateException) {}
        assertTrue(rig.waitFor("open2")) // переподключение после таймаута удалось
        val old = rig.devices[0].originalListener!!

        val shot = async(Dispatchers.IO) { runCatching { c.capture(target(), LampType.DAYLIGHT) } }
        assertTrue(rig.waitFor("snap2"))
        old(ToupcamController.EVENT_STILLIMAGE)   // запоздавшее событие первого подключения
        Thread.sleep(200)
        assertFalse("старое событие не читает кадр", rig.has("pullStart1"))
        assertFalse("и не завершает новый снимок", shot.isCompleted)

        rig.devices[1].listener!!(ToupcamController.EVENT_STILLIMAGE)
        assertTrue(shot.awaitWithin().isSuccess)
        assertTrue(rig.has("pullEnd2"))
    }
}
