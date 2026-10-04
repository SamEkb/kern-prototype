package com.siams.kern_prototype.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import android.util.Size
import android.view.Surface
import com.siams.kern_prototype.data.settings.CameraProfileStore
import com.siams.kern_prototype.data.storage.FfcFiles
import com.siams.kern_prototype.data.storage.writeAtomically
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject
import kotlin.math.pow

/**
 * Реализация [KernCamera] для внешней USB-камеры **Touptek E3ISPM20000KPA** (§3).
 * Основной путь съёмки: смартфон ↔ камера по USB-OTG через ToupCam SDK (нативный
 * `libtoupcam.so` + мост `libjnicam.so` из `jnicam.cpp` + Java-обёртка
 * [com.droid.usbcam.CamLib], доступ — через [ToupcamDevice]), а не через Camera2 телефона.
 *
 * Последовательность (по официальному демо ToupTek + `toupcam.h`):
 *  1) [open] — найти камеру по USB, получить доступ, открыть и запустить pull-режим
 *     ([CamLibDevice]); применить профиль;
 *  2) [startSession] — кадры EVENT_IMAGE тянем в Bitmap и рисуем на превью-Surface;
 *  3) [capture] — `snap()` полного разрешения → EVENT_STILLIMAGE → Bitmap → JPEG в файл;
 *  4) [applyProfile]/[switchLamp] — мгновенно меняют экспозицию/усиление/WB.
 *
 * Жизненный цикл подключения (п.1–5 ревью):
 *  - подключение — [CameraConnection]: единственный на процесс владелец хэндла SDK; все
 *    обращения к камере (открытие, Snap, настройки, AE/AWB, чтение кадров, закрытие) идут
 *    последовательно в его очереди и не выполняются после закрытия;
 *  - закрытие принадлежит подключению и возможно в любой момент, в том числе во время
 *    подключения; новое подключение начинается только после полного закрытия прежнего;
 *  - снимок учитывается [StillGate]: после неопределённого исхода — только через
 *    переподключение, в том числе если предыдущее переподключение не удалось.
 *
 * §13: вся работа с камерой централизована здесь + в [ToupcamDevice]/[ToupcamProfileApplier].
 */
class ToupcamController internal constructor(
    private val context: Context,
    private val profileStore: CameraProfileStore,
    private val deviceFactory: () -> ToupcamDevice,
    private val snapTimeoutMs: Long,
    private val shutdownWaitMs: Long,
    /** R21: источник метрик памяти для замеров операций (выделен для тестов). */
    private val memoryReader: MemoryReader = MemoryReader.System,
) : KernCamera {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileStore: CameraProfileStore,
    ) : this(context, profileStore, { CamLibDevice(context) }, SNAP_TIMEOUT_MS, SHUTDOWN_WAIT_MS)

    /** FIXES §3: фокус у Touptek механический — профиль его не применяет ([ToupcamProfileApplier]). */
    override val capabilities: CameraCapabilities = CameraCapabilities(supportsFocus = false, terms = ParameterTerms.TOUPTEK)

    private val _state = MutableStateFlow<CameraControllerState>(CameraControllerState.Idle)
    override val state: StateFlow<CameraControllerState> = _state.asStateFlow()

    /** Желаемый профиль (применяется при подключении/переподключении). */
    @Volatile
    private var currentProfile: CameraProfile = CameraProfile.defaultFor(LampType.DAYLIGHT)

    /**
     * R10: профиль, применённый к текущему подключению, и граница применения ([Applied.boundary]):
     * номер поступления последнего события на момент сразу после сеттеров. Засчитываются только
     * кадры, поступившие позже, — пришедшие во время сеттеров не в счёт.
     * Сбрасывается в null ДО вызова сеттеров и выставляется в той же задаче очереди сразу после
     * их успешного завершения (параметры уже в камере): ошибка сеттера оставляет null
     * («неизвестно») — перед снимком профиль будет применён заново. «Применён» ≠ «готов к
     * снимку»: см. [awaitSettled].
     * Оговорка: это завершение вызовов обёртки; принятие параметров SDK (HRESULT/readback) — R14.
     */
    private class Applied(
        val profile: CameraProfile,
        val boundary: Long,
        /** R14: отчёт этого применения (значения после ограничения и результаты сеттеров). */
        val report: ToupcamProfileApplier.ApplyReport?,
    ) {
        /** Кадры превью, ПОСТУПИВШИЕ после [boundary] (считаются в очереди подключения). */
        val framesAfter = java.util.concurrent.atomic.AtomicInteger(0)
    }

    @Volatile
    private var applied: Applied? = null
        set(value) {
            field = value
            if (value != null) lastConfirmed = value.profile
        }

    /** Последний подтверждённо применённый профиль — для отображения (HUD, редактор). */
    @Volatile
    private var lastConfirmed: CameraProfile? = null
    override val activeProfile: CameraProfile get() = applied?.profile ?: lastConfirmed ?: currentProfile

    /**
     * R10/R13: операции с параметрами и снимком (применение профиля, переключение лампы, снимок,
     * эталон FFC, AE, AWB) выполняются строго по одной — снимок не может пройти посреди смены профиля.
     */
    private val opLock = Mutex()

    // Текущее подключение (null — не подключено): владелец хэндла SDK и его очередь операций.
    @Volatile private var connection: CameraConnection? = null

    private var previewSize = Size(1920, 1080)
    /** Размер снимка; null — не определён (снимок не поддерживается или размер не прочитан). */
    private var stillSize: Size? = null
    // Буфер превью используется только очередью подключения; освобождается её закрытием.
    @Volatile private var previewBitmap: Bitmap? = null
    // Буфер снимка переиспользуется в пределах подключения. При переподключении не
    // освобождается явно (им может ещё пользоваться сохранение снимка) — освободит GC.
    @Volatile private var stillBitmap: Bitmap? = null
    // Поверхность, на которую рисуется превью; [sessionSurface] переживает переподключение.
    // R16: отрисовка и смена поверхности — под [surfaceLock]: отсоединение ждёт идущую отрисовку.
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var sessionSurface: Surface? = null
    private val surfaceLock = Any()

    /** Для тестов: поверхность, на которую сейчас рисуется превью. */
    internal val currentPreviewSurface: Surface? get() = previewSurface
    @Volatile private var frameEvents = 0L

    // Кэш загруженного эталона FFC (§2.3), чтобы не декодировать PNG на каждый снимок.
    @Volatile private var cachedTemplate: Bitmap? = null
    @Volatile private var cachedTemplatePath: String? = null

    // Операция снимка, поколения подключений и признак «нужно переподключение».
    private val stillGate = StillGate<Bitmap>()

    // true после release(): никаких переподключений (в т.ч. из снимка, начатого до release).
    @Volatile private var released = false

    override suspend fun open(lampType: LampType) {
        released = false
        if (connection != null) return
        _state.value = CameraControllerState.Opening
        currentProfile = profileStore.read(lampType)
        try {
            connect(currentProfile)
        } catch (t: Throwable) {
            _state.value = CameraControllerState.Error(t.message ?: "Ошибка подключения камеры Touptek")
            throw t
        }
    }

    /**
     * Подключить камеру с [profile]. Сначала — владение камерой на процесс: пока прежнее
     * подключение (этого или другого контроллера) не закрыто целиком, новое не начинается.
     * [release] в любой момент подключения закрывает именно это подключение ([CameraConnection]).
     */
    private suspend fun connect(profile: CameraProfile) {
        var generation = 0L
        val conn = CameraConnection.open(deviceFactory(), shutdownWaitMs) { dev, event, seq, arrivedNs ->
            onSdkEvent(dev, event, generation, seq, arrivedNs)
        }
        generation = stillGate.startConnection() // события начнутся только после open() устройства
        connection = conn
        try {
            check(!released) { "Камера освобождена" } // release() до записи поля — см. выше
            conn.connect()                             // USB-доступ → открыть → поток кадров
            conn.run { dev ->
                readSizes(dev)
                // R14/R17: модель, прошивка, выбранное still-разрешение — в журнал диагностики.
                runCatching { Log.i(TAG, "Камера:\n" + dev.describe().trim()) }
            }
            // R14: границы камеры до применения профиля (чтение только геттерами SDK).
            val limits = CameraLimits.from(readback(conn))
            _limits.value = limits
            Log.i(TAG, "Границы камеры: $limits")
            applyToConnection(conn, profile)
            readback(conn)?.let { Log.i(TAG, "Фактические параметры после подключения: $it") }
            previewBitmap = Bitmap.createBitmap(previewSize.width, previewSize.height, Bitmap.Config.ARGB_8888)
            // release() пришёл, пока шло подключение, — подключение уже закрывается.
            check(!released && connection === conn && !conn.isClosed) { "Камера освобождена" }
            stillGate.connected()
            _state.value = CameraControllerState.Ready(previewSize, manualSupported = true)
        } catch (t: Throwable) {
            closeConnection(conn)
            throw t
        }
    }

    /**
     * Восстановление после снимка с неизвестным исходом или потери подключения: закрыть
     * прежнее подключение (после его текущей операции) и открыть заново с тем же профилем и
     * поверхностью превью. USB-доступ уже выдан. Неудача оставляет признак восстановления —
     * следующий снимок (в т.ч. эталона FFC) снова попробует переподключиться.
     * Способ восстановления требует проверки на телефоне с камерой.
     */
    private suspend fun reconnect() {
        // Освобождённый контроллер камеру не открывает: иначе снимок, начатый до release(),
        // по таймауту занял бы камеру параллельно со следующим экраном.
        check(!released) { "Камера освобождена" }
        val profile = currentProfile
        Log.w(TAG, "Переподключение камеры")
        _state.value = CameraControllerState.Opening
        connection?.let { closeConnection(it) }
        try {
            connect(profile)
        } catch (t: Throwable) {
            stillGate.markRecoveryRequired()
            _state.value = CameraControllerState.Error(
                "Не удалось переподключить камеру: ${t.message}. Повторите снимок для новой попытки.",
                canRetry = true,
            )
            throw t
        }
        synchronized(surfaceLock) { previewSurface = sessionSurface }
    }

    /** Подключение для снимка: если нужно восстановление — сначала переподключение. */
    private suspend fun connectionForSnap(): CameraConnection {
        if (stillGate.needsRecovery) reconnect()
        return connection ?: error("Камера Touptek не открыта")
    }

    override fun detachSurface(surface: Surface) = synchronized(surfaceLock) {
        // Отрисовка идёт под этим же замком: к моменту входа сюда она завершена, а новая на эту
        // поверхность уже не начнётся. Состояние подключения (закрывается/закрыто) не важно.
        if (sessionSurface === surface) sessionSurface = null
        if (previewSurface === surface) previewSurface = null
    }

    /**
     * R16: восстановление по запросу экрана (кнопка «Переподключить», повторное подключение USB):
     * закрыть прежнее подключение, если оно есть, и подключиться заново с желаемым профилем.
     * Под общим замком операций — не пересекается со снимком и применением профиля.
     */
    override suspend fun recover() = opLock.withLock {
        check(!released) { "Камера освобождена" }
        reconnect()
    }

    override suspend fun startSession(surface: Surface) {
        connection ?: error("Камера Touptek не открыта")
        // Поток кадров уже запущен в open(); достаточно дать поверхность для отрисовки.
        synchronized(surfaceLock) {
            sessionSurface = surface
            previewSurface = surface
        }
    }

    override suspend fun applyProfile(profile: CameraProfile) = opLock.withLock { applyLocked(profile) }

    override suspend fun switchLamp(lampType: LampType) = opLock.withLock { applyLocked(profileStore.read(lampType)) }

    /** R14: последний отчёт применения профиля (запрошенные значения SDK и отклонённые сеттеры). */
    @Volatile
    internal var lastApplyReport: ToupcamProfileApplier.ApplyReport? = null
        private set

    /** R14: границы текущей камеры (из SDK при подключении; до того — запасные). */
    private val _limits = MutableStateFlow(CameraLimits.FALLBACK)
    override val limits: StateFlow<CameraLimits> = _limits.asStateFlow()

    /** Применить профиль с границами камеры и зафиксировать результаты сеттеров SDK (R14). */
    private fun applyAndReport(dev: ToupcamDevice, profile: CameraProfile) {
        val report = ToupcamProfileApplier.apply(dev, profile, _limits.value)
        lastApplyReport = report
        if (report.rejected.isNotEmpty()) Log.w(TAG, "SDK отклонил параметры профиля ${profile.lampType}: ${report.rejected}")
    }

    /** R14: вызов SDK в авто-процедуре — операция, аргументы, HRESULT. */
    @kotlinx.serialization.Serializable
    data class SdkCall(val op: String, val args: String, val hrValue: Int) {
        /** HRESULT: неотрицательный — успех (отрицательные бывают и 0x8…, и 0x9…–0xF…). */
        val ok: Boolean get() = hrValue >= 0
        val hr: String get() = "0x%08x".format(hrValue)
    }

    /** R14: вызовы SDK последней авто-процедуры (AE/AWB) — для журнала диагностики и аудита. */
    @Volatile
    internal var lastAutoCalls: List<SdkCall> = emptyList()
        private set

    /** Точка для тестов: выполнить [block] в очереди подключения (задержать очередь). В приложении не используется. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun <T> runOnConnectionForTest(block: (ToupcamDevice) -> T): T =
        (connection ?: error("нет подключения")).run(block)

    /** Точка для тестов: перед записью события в буфер (медленная обработка). В приложении null. */
    @androidx.annotation.VisibleForTesting
    @Volatile
    internal var beforeEventRecordedForTest: ((Int) -> Unit)? = null

    /** 5k: итог последней авто-процедуры (AE/AWB) — диагностика WB/AE. */
    @Volatile
    internal var lastAutoSummary: AutoProcedureSummary? = null

    /** 5k: событие SDK (кроме кадров) с номером и монотонным временем ПОСТУПЛЕНИЯ ([CameraConnection.post]). */
    private class ArrivedEvent(val generation: Long, val seq: Long, val code: Int, val arrivedNs: Long)

    /** 5k: последние события SDK — кольцевой буфер; итог процедуры выбирает их по номерам поступления. */
    private val recentEvents = ArrayDeque<ArrivedEvent>()

    /** Размер буфера событий (для тестов вытеснения — меньше). */
    @androidx.annotation.VisibleForTesting
    internal var eventBufferSize: Int = EVENT_BUFFER

    /**
     * Окно авто-процедуры: события с номером больше [startSeq]. [firstEvictedSeq] — первый
     * ФАКТИЧЕСКИ вытесненный из буфера номер этой части (номера получают и кадры, которые в буфер
     * не попадают, поэтому разрыв номеров вытеснения не доказывает).
     */
    private class EventWindow(val generation: Long, val startSeq: Long) {
        var firstEvictedSeq: Long? = null
    }

    /** Открытые окна процедур (доступ — под замком [recentEvents]). */
    private val openWindows = mutableListOf<EventWindow>()

    /** 5k: коды событий, уже записанные в журнал (первое появление за подключение). */
    private val loggedEvents: MutableSet<Pair<Long, Int>> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * 5k: событие SDK, кроме кадров превью/снимка: в буфер (для итогов процедур) и в журнал при
     * первом появлении кода за подключение (частые события не засоряют журнал).
     */
    private fun noteEvent(event: Int, generation: Long, seq: Long, arrivedNs: Long) {
        beforeEventRecordedForTest?.invoke(event)
        synchronized(recentEvents) {
            recentEvents.addLast(ArrivedEvent(generation, seq, event, arrivedNs))
            while (recentEvents.size > eventBufferSize) {
                val evicted = recentEvents.removeFirst()
                openWindows.forEach { w ->
                    if (w.firstEvictedSeq == null && evicted.generation == w.generation && evicted.seq > w.startSeq) {
                        w.firstEvictedSeq = evicted.seq
                    }
                }
            }
        }
        if (loggedEvents.add(generation to event)) {
            runCatching { Log.i(TAG, "Событие SDK ${SdkEvents.name(event)} (0x%04x) — первое за подключение".format(event)) }
        }
    }

    private fun openWindow(generation: Long, startSeq: Long): EventWindow =
        synchronized(recentEvents) { EventWindow(generation, startSeq).also { openWindows += it } }

    /**
     * Закрыть окно: события с номерами поступления в (startSeq, endSeq] и признак, что событие
     * окна ФАКТИЧЕСКИ вытеснено из буфера.
     */
    private fun closeWindow(w: EventWindow, endSeq: Long, startNs: Long): Pair<List<SdkEventRecord>, Boolean> =
        synchronized(recentEvents) {
            openWindows.remove(w)
            val inWindow = recentEvents.filter { it.generation == w.generation && it.seq in (w.startSeq + 1)..endSeq }
            val truncated = w.firstEvictedSeq?.let { it <= endSeq } == true
            inWindow.map { SdkEventRecord(it.code, SdkEvents.name(it.code), (it.arrivedNs - startNs) / 1_000_000) } to truncated
        }

    /** Записать вызов SDK авто-процедуры; поведение не меняется (отказ только фиксируется). */
    private fun sdk(calls: MutableList<SdkCall>, op: String, args: String, hr: Int) {
        val call = SdkCall(op, args, hr)
        calls += call
        if (hr < 0) Log.w(TAG, "SDK отклонил $op($args): ${call.hr}") else Log.i(TAG, "SDK $op($args) → ${call.hr}")
    }

    /** R14: фактическое состояние параметров по данным SDK (в очереди подключения). */
    private suspend fun readback(conn: CameraConnection): CameraReadback? =
        runCatching { conn.run { CameraReadback.parse(it.readState()) } }.getOrNull()

    /** Применить профиль (под [opLock]). */
    private suspend fun applyLocked(profile: CameraProfile) {
        currentProfile = profile
        val conn = connection ?: return
        try {
            applyToConnection(conn, profile)
        } catch (e: IllegalStateException) {
            if (!conn.isClosed) throw e // камера освобождена — применять некуда (как раньше без камеры)
        }
    }

    /**
     * Единственный путь смены параметров камеры: [applied] сбрасывается ДО сеттеров и выставляется
     * только после их успешного выполнения ([block] — сеттеры; возвращает применённый профиль).
     */
    private suspend fun applyToConnection(
        conn: CameraConnection,
        profile: CameraProfile,
        block: (ToupcamDevice) -> CameraProfile = { dev -> applyAndReport(dev, profile); profile },
    ): CameraProfile {
        applied = null
        return conn.run { dev ->
            val result = block(dev)
            // Сеттеры выполнены: граница — после них; события, поступившие во время сеттеров, раньше неё.
            applied = Applied(result, conn.arrivalBoundary(), lastApplyReport)
            result
        }
    }

    /**
     * R10: снимок делается ровно с профилем лампы [lampType] и только когда он ГОТОВ: применён (при
     * неизвестном исходе прошлого применения — применяется заново) и после его сеттеров пришло
     * [SETTLE_FRAMES] кадров превью. Возвращает применённый профиль.
     */
    private suspend fun ensureLampApplied(conn: CameraConnection, lampType: LampType): Applied {
        val current = applied?.takeIf { it.profile.lampType == lampType }
        val ready = current ?: run {
            val profile = profileStore.read(lampType)
            currentProfile = profile
            applyToConnection(conn, profile)
            applied ?: error("Профиль не применён")
        }
        awaitSettled(ready)
        return ready
    }

    /**
     * Дождаться [SETTLE_FRAMES] кадров превью после применения [a]. Не дождались за
     * [SETTLE_TIMEOUT_MS] — ошибка: снимать с неподтверждёнными параметрами нельзя.
     * Состояние ожидания хранится с применением — отменённое ожидание продолжится при следующем снимке.
     */
    private suspend fun awaitSettled(a: Applied) {
        val ok = withTimeoutOrNull(SETTLE_TIMEOUT_MS) {
            while (a.framesAfter.get() < SETTLE_FRAMES) delay(FRAME_POLL_MS)
        }
        if (ok == null) {
            throw IllegalStateException(
                "Камера не подтвердила новые параметры (нет кадров превью ${SETTLE_TIMEOUT_MS / 1000} с) — снимок не выполнен",
            )
        }
    }


    /**
     * §2.1: автоэкспозиция «в одно нажатие». Приоритет — минимизировать выдержку не выходит:
     * наоборот, gain ограничиваем сверху (меньше зерна), поэтому AE предпочтёт удлинить
     * выдержку. Даём авто сойтись, читаем выдержку/усиление, ЗАМОРАЖИВАЕМ авто (детерминизм,
     * §6.2) и возвращаем профиль с подставленными значениями.
     */
    override suspend fun autoExposure(current: CameraProfile): CameraProfile = opLock.withLock {
        val conn = connection ?: return@withLock current
        autoProcedure("Автоэкспозиция", conn, current) { calls ->
            // Вызовы SDK и их порядок — прежние; каждый шаг — в очереди подключения.
            applied = null                     // авто-режим: параметры камеры меняются сами
            conn.run { cam ->
                sdk(calls, "MaxAutoExpoTimeAGain", "$AE_MAX_TIME_US us, $AE_MAX_GAIN_PERCENT %",
                    cam.setMaxAutoExpoTimeAGain(AE_MAX_TIME_US, AE_MAX_GAIN_PERCENT))
                sdk(calls, "AutoExpoEnable", "1", cam.setAutoExpoEnable(1)) // включить авто-AE
            }
            delay(AE_CONVERGE_MS)              // дать сойтись (~кадры на нашем fps)
            val updated = applyToConnection(conn, current) { cam ->
                val us = cam.getExpoTime()
                val gainPct = cam.getExpoAGain()
                sdk(calls, "AutoExpoEnable", "0", cam.setAutoExpoEnable(0)) // заморозить — дальше только ручное
                current.copy(
                    exposureTimeNs = us.toLong() * 1_000L,
                    iso = gainPct,
                ).also { applyAndReport(cam, it) }
            }
            currentProfile = updated
            updated
        }
    }

    /**
     * §2.2: автобаланс белого «в одно нажатие». Запускаем one-push AWB, ждём применения,
     * читаем поканальные усиления и переводим их в множители профиля (обратно к
     * [ToupcamProfileApplier.toWbGain]: multiplier = 2^(gain/64)). Зелёный — опорный (1.0).
     */
    override suspend fun autoWhiteBalance(current: CameraProfile): CameraProfile = opLock.withLock {
        val conn = connection ?: return@withLock current
        autoProcedure("Автобаланс белого", conn, current) { calls ->
            applied = null                     // one-push AWB меняет баланс белого сам
            conn.run { sdk(calls, "AwbInit", "one-push", it.awbOnce()) }
            delay(AWB_SETTLE_MS)
            val updated = applyToConnection(conn, current) { cam ->
                val g = cam.getWhiteBalanceGain()  // [R, G, B], знаковые (-127..127)
                val red = 2.0.pow((g.getOrElse(0) { 0 }) / 64.0).toFloat()
                val blue = 2.0.pow((g.getOrElse(2) { 0 }) / 64.0).toFloat()
                current.copy(
                    wbRedGain = red,
                    wbGreenEvenGain = 1f,
                    wbGreenOddGain = 1f,
                    wbBlueGain = blue,
                ).also { applyAndReport(cam, it) }
            }
            currentProfile = updated
            updated
        }
    }

    /**
     * Обвязка авто-процедуры (AE/AWB): вызовы [body] не меняются. R13: прерванная процедура
     * (ошибка/отмена) возвращает ручной режим с прежним профилем. 5k: события SDK за время процедуры
     * собираются, ПОСЛЕ неё (и после возврата ручного режима) читается состояние SDK, итог —
     * [lastAutoSummary] и одна строка журнала.
     */
    private suspend fun autoProcedure(
        name: String,
        conn: CameraConnection,
        current: CameraProfile,
        body: suspend (MutableList<SdkCall>) -> CameraProfile,
    ): CameraProfile {
        val calls = java.util.Collections.synchronizedList(mutableListOf<SdkCall>())
        // Начало окна: время, затем граница поступлений — событие между ними попадёт в окно
        // с неотрицательной отметкой. Принадлежность — по номеру ПОСТУПЛЕНИЯ, не обработки.
        val startNs = System.nanoTime()
        val generation = stillGate.currentGeneration()
        val window = openWindow(generation, conn.arrivalBoundary())
        var result: CameraProfile? = null
        var outcome = "ошибка"
        try {
            return body(calls).also { result = it; outcome = "завершена" }
        } catch (e: CancellationException) {
            outcome = "отменена"
            throw e
        } catch (t: Throwable) {
            outcome = "ошибка: ${t.message ?: t.javaClass.simpleName}"
            throw t
        } finally {
            lastAutoCalls = calls.toList()
            if (result == null) restoreManual(conn, current)
            // Конец окна — после последнего рабочего вызова (и возврата ручного режима). Чтение
            // состояния идёт той же очередью ПОСЛЕ фиксации границы, поэтому к его завершению все
            // события окна уже обработаны (в буфере).
            val endSeq = conn.arrivalBoundary()
            val after = withContext(NonCancellable) { readback(conn) }
            val (events, truncated) = closeWindow(window, endSeq, startNs)
            val summary = AutoProcedureSummary(
                procedure = name,
                outcome = outcome,
                durationMs = (System.nanoTime() - startNs) / 1_000_000,
                calls = calls.toList(),
                result = result?.let {
                    AutoProcedureSummary.Result(
                        exposureUs = it.exposureTimeNs / 1_000L,
                        gainPercent = it.iso,
                        wbRed = it.wbRedGain,
                        wbGreen = (it.wbGreenEvenGain + it.wbGreenOddGain) / 2f,
                        wbBlue = it.wbBlueGain,
                    )
                },
                readbackAfter = after,
                events = events,
                eventsTruncated = truncated,
            )
            lastAutoSummary = summary
            runCatching { Log.i(TAG, summary.describe()) }
        }
    }

    /** R13: вернуть ручной режим и прежний профиль после прерванной авто-процедуры (не отменяется). */
    private suspend fun restoreManual(conn: CameraConnection, profile: CameraProfile) = withContext(NonCancellable) {
        runCatching { applyToConnection(conn, profile) }
            .onFailure { Log.w(TAG, "Не удалось вернуть ручной режим: ${it.message}") }
    }


    override suspend fun capture(target: File, lampType: LampType): CaptureOutcome = opLock.withLock {
        withContext(Dispatchers.IO) {
            // R21: замер памяти снимка (только измерение; обработка кадра не меняется).
            val probe = MemoryProbe("capture", memoryReader)
            var outcome = "error"
            try {
                captureLocked(target, lampType, probe).also { outcome = "ok" }
            } finally {
                probe.finish(outcome)
            }
        }
    }

    private suspend fun captureLocked(target: File, lampType: LampType, probe: MemoryProbe): CaptureOutcome {
        val conn = connectionForSnap()
        val ready = ensureLampApplied(conn, lampType) // R10: снимок — с профилем именно этой лампы
        probe.mark("applied")
        val profile = ready.profile
        // R14: всё для аудита — в этой же операции (под opLock): отчёт применения этого профиля,
        // границы и состояние SDK непосредственно перед Snap.
        val audit = CaptureAudit(
            requested = CaptureAudit.Requested(
                exposureTimeNs = profile.exposureTimeNs,
                iso = profile.iso,
                gamma = profile.gamma,
                wbRedGain = profile.wbRedGain,
                wbGreenGain = (profile.wbGreenEvenGain + profile.wbGreenOddGain) / 2f,
                wbBlueGain = profile.wbBlueGain,
            ),
            applied = ready.report,
            limits = _limits.value,
            readback = readback(conn),
        )
        val still = snapStillBlocking(conn)
        probe.mark("pulled")
        if (applyFfcIfConfigured(conn, still, profile)) probe.mark("ffc") // §2.3: нормализация делением на эталон
        // R09: неудачный compress — ошибка съёмки; R08: файл появляется целиком или никак.
        writeAtomically(target) { out ->
            still.compress(Bitmap.CompressFormat.JPEG, profile.jpegQuality.coerceIn(1, 100), out)
        }
        val memory = probe.finish("ok", bufferEstimates(still))
        return CaptureOutcome(profile, audit.copy(memory = memory))
    }

    /**
     * R21: размеры буферов снимка (живут всё подключение): Bitmap снимка — фактический размер
     * выделения; «rgb24FrameBytes» — размер кадра RGB24 (со строками, выровненными на 4 байта).
     * Это НЕ ёмкость нативного буфера моста: он общий для превью и снимка, только растёт и может
     * быть больше (после другого разрешения/подключения); сама ёмкость отсюда не читается.
     */
    private fun bufferEstimates(still: Bitmap): Map<String, Long> = buildMap {
        put("stillBitmap", still.allocationByteCount.toLong())
        put("rgb24FrameBytes", ((still.width * 24L + 31) / 32 * 4) * still.height)
        previewBitmap?.let { put("previewBitmap", it.allocationByteCount.toLong()) }
    }

    /**
     * §2.3: снять эталонный кадр FFC. Делаем полнокадровый снимок белого фона,
     * уменьшаем (усреднение уже подавляет пыль) и слегка размываем — получаем шаблон
     * неравномерности; сохраняем PNG рядом в filesDir (свой на профиль). Путь пишем в профиль.
     */
    override suspend fun captureFfcReference(lampType: LampType): FfcReference = opLock.withLock {
        withContext(Dispatchers.IO) {
            // R21: замер памяти эталона FFC — в журнал (KernMemory); обработка не меняется.
            val probe = MemoryProbe("ffc-reference", memoryReader)
            var outcome = "error"
            try {
                captureFfcLocked(lampType, probe).also { outcome = "ok" }
            } finally {
                lastFfcMemory = probe.finish(outcome)
            }
        }
    }

    /** R21: замер памяти последнего эталона FFC (диагностика). */
    @Volatile
    internal var lastFfcMemory: MemoryTrace? = null
        private set

    /** R22: отчёт проверки последнего эталона FFC (принятого или отклонённого; диагностика). */
    @Volatile
    internal var lastFfcQuality: FfcQuality.Report? = null
        private set

    private suspend fun captureFfcLocked(lampType: LampType, probe: MemoryProbe): FfcReference {
        val conn = connectionForSnap()
        ensureLampApplied(conn, lampType) // эталон — с профилем этой лампы (в редакторе — рабочим)
        probe.mark("applied")
        val still = snapStillBlocking(conn)
        probe.mark("pulled")
        val h = (FFC_TEMPLATE_WIDTH.toLong() * still.height / still.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(still, FFC_TEMPLATE_WIDTH, h, true)
        val template = boxBlur(small, FFC_BLUR_RADIUS, FFC_BLUR_PASSES)
        if (template !== small) small.recycle()
        probe.mark("template")
        // R22: проверка качества ДО записи файла — по полному кадру и готовому шаблону.
        val quality = FfcQuality.evaluate(lampType, still, template)
        lastFfcQuality = quality
        Log.i(TAG, "FFC ${lampType.name}: ${if (quality.accepted) "принят" else "отклонён"} — ${quality.summary()}")
        probe.mark("quality")
        if (!quality.accepted) {
            template.recycle()
            throw FfcQuality.RejectedException(quality) // файл не создаётся: прежний эталон не затронут
        }
        // R11: всегда новый файл — эталон подтверждённого профиля не перезаписывается.
        // Профиль переключится на него только при «Сохранить».
        val file = FfcFiles.newFile(context, lampType)
        writeAtomically(file) { template.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // подхватить свежий шаблон (прежний не освобождаем явно — может использоваться снимком)
        synchronized(this@ToupcamController) {
            cachedTemplate = template; cachedTemplatePath = file.absolutePath
        }
        return FfcReference(file.absolutePath, quality)
    }

    /**
     * Полнокадровый снимок с ожиданием EVENT_STILLIMAGE (§3c). Возвращает буфер с кадром.
     *
     * Операция занята до конца ЧТЕНИЯ кадра. Таймаут или отмена (в том числе во время чтения)
     * оставляют исход неизвестным: запоздавшее событие/чтение не должно завершить следующий
     * снимок, поэтому камера переводится в режим восстановления (переподключение).
     */
    private suspend fun snapStillBlocking(conn: CameraConnection): Bitmap {
        val still = ensureStillBitmap()
        val done = CompletableDeferred<Unit>()
        val pending = stillGate.begin(still) { result ->
            result.fold({ done.complete(Unit) }, { done.completeExceptionally(it) })
        }
        // Snap — в очереди подключения (не пересекается с закрытием камеры).
        val accepted = try {
            conn.run { dev -> runCatching { dev.snap() }.getOrDefault(false) }
        } catch (e: CancellationException) {
            stillGate.abandon(pending) // Snap мог успеть выполниться — исход неизвестен
            throw e
        } catch (e: Throwable) {
            stillGate.rejected(pending) // подключение закрыто — Snap не выполнялся
            throw e
        }
        // R09: отказ Snap в SDK — ошибка, а не ожидание до таймаута (события не будет).
        if (!accepted) {
            stillGate.rejected(pending)
            error("Камера отклонила снимок (Snap)")
        }
        try {
            withTimeout(snapTimeoutMs) { done.await() }
        } catch (e: TimeoutCancellationException) {
            stillGate.abandon(pending)
            val recovered = runCatching { reconnect() }.isSuccess
            throw IllegalStateException(
                "Камера не вернула снимок за ${snapTimeoutMs / 1000} с. " +
                    if (recovered) "Камера переподключена — повторите снимок." else "Повторите снимок для переподключения.",
                e,
            )
        } catch (e: CancellationException) {
            stillGate.abandon(pending) // событие/чтение может ещё завершиться — до восстановления не снимать
            throw e
        }
        return still
    }

    /**
     * §2.3: если у профиля задан эталон — нормализовать снимок делением (нативно, in-place).
     * Эталон не задан — снимок без коррекции (как раньше). Эталон задан, но не читается или
     * коррекция не выполнена — ОШИБКА снимка: молча снять без настроенной коррекции нельзя
     * (снимки стали бы несопоставимы).
     */
    private fun applyFfcIfConfigured(conn: CameraConnection, still: Bitmap, profile: CameraProfile): Boolean {
        val path = profile.ffcTemplatePath
        if (path.isBlank()) return false
        val tmpl = loadTemplate(path) ?: error(
            "Эталон FFC профиля «${profile.lampType.ruLabel}» не читается. " +
                "Переснимите эталон в «Профилях камеры» и сохраните профиль.",
        )
        // Коррекция работает только с пикселями буферов (хэндл камеры не нужен) — вне очереди.
        check(conn.device.applyFlatField(still, tmpl)) { "Не удалось применить коррекцию FFC к снимку" }
        return true
    }

    private fun loadTemplate(path: String): Bitmap? = synchronized(this) {
        if (cachedTemplatePath == path && cachedTemplate?.isRecycled == false) return cachedTemplate
        val bmp = FfcFiles.decodeTemplate(path) ?: return null
        cachedTemplate = bmp
        cachedTemplatePath = path
        bmp
    }

    /** Разделяемый box-blur (несколько проходов ≈ гаусс) на маленьком шаблоне FFC. */
    private fun boxBlur(src: Bitmap, radius: Int, passes: Int): Bitmap {
        val w = src.width
        val h = src.height
        if (radius < 1 || w < 3 || h < 3) return src
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        src.getPixels(a, 0, w, 0, 0, w, h)
        repeat(passes) {
            boxBlurH(a, b, w, h, radius)
            boxBlurV(b, a, w, h, radius)
        }
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { it.setPixels(a, 0, w, 0, 0, w, h) }
    }

    private fun boxBlurH(inp: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var rr = 0; var gg = 0; var bb = 0; var cnt = 0
                var k = x - r
                while (k <= x + r) {
                    val kk = if (k < 0) 0 else if (k >= w) w - 1 else k
                    val p = inp[row + kk]
                    rr += (p ushr 16) and 0xFF; gg += (p ushr 8) and 0xFF; bb += p and 0xFF; cnt++
                    k++
                }
                out[row + x] = (0xFF shl 24) or ((rr / cnt) shl 16) or ((gg / cnt) shl 8) or (bb / cnt)
            }
        }
    }

    private fun boxBlurV(inp: IntArray, out: IntArray, w: Int, h: Int, r: Int) {
        for (x in 0 until w) {
            for (y in 0 until h) {
                var rr = 0; var gg = 0; var bb = 0; var cnt = 0
                var k = y - r
                while (k <= y + r) {
                    val kk = if (k < 0) 0 else if (k >= h) h - 1 else k
                    val p = inp[kk * w + x]
                    rr += (p ushr 16) and 0xFF; gg += (p ushr 8) and 0xFF; bb += p and 0xFF; cnt++
                    k++
                }
                out[y * w + x] = (0xFF shl 24) or ((rr / cnt) shl 16) or ((gg / cnt) shl 8) or (bb / cnt)
            }
        }
    }


    override fun release() {
        released = true
        sessionSurface = null
        connection?.let { closeConnection(it) }
        _state.value = CameraControllerState.Idle
    }

    // ---- События SDK (очередь CameraConnection подключения) ---------------------

    private fun onSdkEvent(dev: ToupcamDevice, event: Int, generation: Long, seq: Long, arrivedNs: Long) {
        // Событие прежнего подключения — не относится к текущей камере.
        if (generation != stillGate.currentGeneration()) {
            Log.w(TAG, "Событие 0x${Integer.toHexString(event)} прежнего подключения — проигнорировано")
            return
        }
        if (event != EVENT_IMAGE && event != EVENT_STILLIMAGE) noteEvent(event, generation, seq, arrivedNs)
        when (event) {
            EVENT_IMAGE -> {
                frameEvents++
                // R10: кадр засчитывается применению, только если поступил после его границы.
                applied?.let { if (seq > it.boundary) it.framesAfter.incrementAndGet() }
                val bmp = previewBitmap ?: return
                // R16: чтение кадра и отрисовка — под замком поверхности (см. detachSurface).
                synchronized(surfaceLock) {
                    val surface = previewSurface
                    if (surface == null) {
                        if (frameEvents == 1L || frameEvents % 30 == 0L) {
                            Log.i(TAG, "EVENT_IMAGE #$frameEvents: previewSurface=null (кадр отброшен)")
                        }
                        return
                    }
                    if (frameEvents == 1L || frameEvents % 30 == 0L) {
                        Log.i(TAG, "EVENT_IMAGE #$frameEvents: рисуем $previewSize")
                    }
                    dev.pullImage(bmp)
                    blit(bmp, surface)
                }
            }

            EVENT_STILLIMAGE -> {
                // Только ожидаемый снимок текущего подключения; операция остаётся занятой до
                // конца чтения. Позднее событие (после таймаута) не завершает новую операцию.
                val pending = stillGate.take(generation)
                if (pending == null) {
                    Log.w(TAG, "EVENT_STILLIMAGE без ожидающего снимка — проигнорировано")
                    return
                }
                // R09: успех — только если SDK действительно отдал новый кадр; иначе в буфере
                // остался бы прошлый снимок, который сохранился бы под новым слоем.
                val result = runCatching {
                    check(dev.pullStillImage(pending.target)) { "Камера не отдала снимок (PullStillImage)" }
                }
                stillGate.finish(pending, result)
            }

            EVENT_ERROR, EVENT_DISCONNECTED -> {
                stillGate.fail(IllegalStateException("Камера отключена во время снимка"))
                _state.value = CameraControllerState.Error(
                    "Камера Touptek отключена. Подключите её и повторите снимок.",
                    canRetry = true,
                )
            }
        }
    }

    /** Нарисовать кадр превью на Surface через Canvas. */
    private fun blit(bmp: Bitmap, surface: Surface) {
        try {
            val canvas = surface.lockCanvas(null) ?: return
            try {
                // FIXES §1: защита — при любой форме поверхности кадр не искажается (fit-center на чёрном).
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(bmp, null, fitCenter(bmp.width, bmp.height, canvas.width, canvas.height), null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Отрисовка превью: ${t.message}")
        }
    }

    // ---- Подключение USB и размеры ---------------------------------------------



    // ---- Размеры и буферы ---------------------------------------------------------

    /**
     * R17: размеры кадров текущего подключения. Размер превью обязателен — не прочитан → ошибка
     * подключения (запасные/прежние значения не используются). Размер снимка не прочитан →
     * снимок недоступен ([ensureStillBitmap] откажет до Snap).
     */
    private fun readSizes(dev: ToupcamDevice) {
        val p = dev.previewSize()?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }
            ?: throw IllegalStateException("Не удалось определить размер кадра превью камеры")
        previewSize = Size(p[0], p[1])
        stillSize = dev.stillSize()?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let { Size(it[0], it[1]) }
        if (stillSize == null) Log.e(TAG, "Размер снимка не определён — снимок недоступен")
        Log.i(TAG, "Touptek: preview=$previewSize still=$stillSize")
        // FIXES §1 п.3 (диагностика): разные пропорции превью и снимка — кадрирование превью не
        // совпадёт со снимком. Выбор разрешения превью (jnicam.cpp) меняется после проверки на камере.
        stillSize?.let { st ->
            val pr = previewSize.width.toDouble() / previewSize.height
            val sr = st.width.toDouble() / st.height
            if (kotlin.math.abs(pr - sr) / sr > 0.01) {
                Log.w(TAG, "Пропорции превью ${"%.3f".format(pr)} и снимка ${"%.3f".format(sr)} различаются — кадрирование превью не совпадёт со снимком")
            }
        }
    }

    private fun ensureStillBitmap(): Bitmap {
        val size = stillSize ?: throw IllegalStateException("Снимок недоступен: размер кадра снимка камеры не определён")
        val existing = stillBitmap
        if (existing != null && existing.width == size.width && existing.height == size.height) {
            return existing
        }
        return Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            .also { stillBitmap = it }
    }

    /**
     * Закрыть подключение [conn] (идемпотентно). Закрытие принадлежит самому подключению: камера
     * закрывается в его очереди после текущей операции, даже если [conn] ещё подключается.
     * Состояние контроллера сбрасывается, только если [conn] — текущее подключение. Буферы снимка
     * и эталона лишь отпускаются (ими может пользоваться сохранение снимка) — освободит GC.
     */
    private fun closeConnection(conn: CameraConnection) {
        var preview: Bitmap? = null
        if (connection === conn) {
            stillGate.fail(IllegalStateException("Камера освобождена"))
            applied = null
            preview = previewBitmap
            connection = null
            previewSurface = null
            previewBitmap = null
            stillBitmap = null
            synchronized(this) {
                cachedTemplate = null
                cachedTemplatePath = null
            }
        }
        conn.close { runCatching { preview?.recycle() } }
    }

    companion object {
        private const val TAG = "ToupcamController"

        // Коды событий toupcam.h.
        internal const val EVENT_IMAGE = 0x0004
        internal const val EVENT_STILLIMAGE = 0x0005
        internal const val EVENT_ERROR = 0x0080
        internal const val EVENT_DISCONNECTED = 0x0081

        internal const val SNAP_TIMEOUT_MS = 15_000L

        /** 5k: сколько последних событий SDK (кроме кадров) хранить для итогов процедур. */
        private const val EVENT_BUFFER = 2048

        // R10: после смены параметров перед снимком ждём кадры превью (проверить на камере).
        private const val SETTLE_FRAMES = 2
        private const val SETTLE_TIMEOUT_MS = 2_000L
        private const val FRAME_POLL_MS = 20L

        /** Сколько ждать завершения обработчика прежнего подключения перед новым (п.3). */
        internal const val SHUTDOWN_WAIT_MS = 10_000L

        // Авто-AE (§2.1): низкий потолок усиления → приоритет выдержке; лимит выдержки и
        // время сходимости подобраны с запасом (керн статичен, длинная выдержка допустима).
        private const val AE_MAX_TIME_US = 1_000_000     // до 1 c выдержки
        private const val AE_MAX_GAIN_PERCENT = 400      // до 4x усиления (меньше зерна)
        private const val AE_CONVERGE_MS = 1_800L        // дать авто-AE сойтись
        private const val AWB_SETTLE_MS = 600L           // применение one-push AWB

        // FFC (§2.3): шаблон неравномерности низкочастотный → храним уменьшенным + размытым.
        private const val FFC_TEMPLATE_WIDTH = 512
        private const val FFC_BLUR_RADIUS = 6
        private const val FFC_BLUR_PASSES = 3
    }
}

/**
 * FIXES §1: прямоугольник для [srcW]×[srcH], вписанный в [dstW]×[dstH] с сохранением пропорций и
 * по центру (fit-center). Пустой источник или приёмник — весь приёмник.
 */
internal fun fitCenter(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Rect {
    if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return Rect(0, 0, maxOf(dstW, 0), maxOf(dstH, 0))
    return if (srcW.toLong() * dstH > srcH.toLong() * dstW) {
        val h = (srcH.toLong() * dstW / srcW).toInt()   // источник шире: по ширине, поля сверху/снизу
        val top = (dstH - h) / 2
        Rect(0, top, dstW, top + h)
    } else {
        val w = (srcW.toLong() * dstH / srcH).toInt()   // источник уже: по высоте, поля слева/справа
        val left = (dstW - w) / 2
        Rect(left, 0, left + w, dstH)
    }
}
