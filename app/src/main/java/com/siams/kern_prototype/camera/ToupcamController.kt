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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
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
) : KernCamera {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        profileStore: CameraProfileStore,
    ) : this(context, profileStore, { CamLibDevice(context) }, SNAP_TIMEOUT_MS, SHUTDOWN_WAIT_MS)

    private val _state = MutableStateFlow<CameraControllerState>(CameraControllerState.Idle)
    override val state: StateFlow<CameraControllerState> = _state.asStateFlow()

    @Volatile
    private var currentProfile: CameraProfile = CameraProfile.defaultFor(LampType.DAYLIGHT)
    override val activeProfile: CameraProfile get() = currentProfile

    // Текущее подключение (null — не подключено): владелец хэндла SDK и его очередь операций.
    @Volatile private var connection: CameraConnection? = null

    private var previewSize = Size(1920, 1080)
    private var stillSize = Size(1920, 1080)
    // Буфер превью используется только очередью подключения; освобождается её закрытием.
    @Volatile private var previewBitmap: Bitmap? = null
    // Буфер снимка переиспользуется в пределах подключения. При переподключении не
    // освобождается явно (им может ещё пользоваться сохранение снимка) — освободит GC.
    @Volatile private var stillBitmap: Bitmap? = null
    // Поверхность, на которую рисуется превью; [sessionSurface] переживает переподключение.
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var sessionSurface: Surface? = null
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
        val conn = CameraConnection.open(deviceFactory(), shutdownWaitMs) { dev, event ->
            onSdkEvent(dev, event, generation)
        }
        generation = stillGate.startConnection() // события начнутся только после open() устройства
        connection = conn
        try {
            check(!released) { "Камера освобождена" } // release() до записи поля — см. выше
            conn.connect()                             // USB-доступ → открыть → поток кадров
            conn.run { dev ->
                readSizes(dev)
                ToupcamProfileApplier.apply(dev, profile)
            }
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
        previewSurface = sessionSurface
    }

    /** Подключение для снимка: если нужно восстановление — сначала переподключение. */
    private suspend fun connectionForSnap(): CameraConnection {
        if (stillGate.needsRecovery) reconnect()
        return connection ?: error("Камера Touptek не открыта")
    }

    override suspend fun startSession(surface: Surface) {
        connection ?: error("Камера Touptek не открыта")
        // Поток кадров уже запущен в open(); достаточно дать поверхность для отрисовки.
        sessionSurface = surface
        previewSurface = surface
    }

    override suspend fun applyProfile(profile: CameraProfile) {
        currentProfile = profile
        val conn = connection ?: return
        try {
            conn.run { ToupcamProfileApplier.apply(it, profile) }
        } catch (e: IllegalStateException) {
            if (!conn.isClosed) throw e // камера освобождена — применять некуда (как раньше без камеры)
        }
    }

    override suspend fun switchLamp(lampType: LampType) {
        applyProfile(profileStore.read(lampType))
    }


    /**
     * §2.1: автоэкспозиция «в одно нажатие». Приоритет — минимизировать выдержку не выходит:
     * наоборот, gain ограничиваем сверху (меньше зерна), поэтому AE предпочтёт удлинить
     * выдержку. Даём авто сойтись, читаем выдержку/усиление, ЗАМОРАЖИВАЕМ авто (детерминизм,
     * §6.2) и возвращаем профиль с подставленными значениями.
     */
    override suspend fun autoExposure(current: CameraProfile): CameraProfile {
        val conn = connection ?: return current
        // Вызовы SDK и их порядок — прежние; каждый шаг — в очереди подключения.
        conn.run { cam ->
            cam.setMaxAutoExpoTimeAGain(AE_MAX_TIME_US, AE_MAX_GAIN_PERCENT)
            cam.setAutoExpoEnable(1)           // включить авто-AE
        }
        delay(AE_CONVERGE_MS)                  // дать сойтись (~кадры на нашем fps)
        val updated = conn.run { cam ->
            val us = cam.getExpoTime()
            val gainPct = cam.getExpoAGain()
            cam.setAutoExpoEnable(0)           // заморозить — дальше только ручное
            current.copy(
                exposureTimeNs = us.toLong() * 1_000L,
                iso = gainPct,
            ).also { ToupcamProfileApplier.apply(cam, it) }
        }
        currentProfile = updated
        return updated
    }

    /**
     * §2.2: автобаланс белого «в одно нажатие». Запускаем one-push AWB, ждём применения,
     * читаем поканальные усиления и переводим их в множители профиля (обратно к
     * [ToupcamProfileApplier.toWbGain]: multiplier = 2^(gain/64)). Зелёный — опорный (1.0).
     */
    override suspend fun autoWhiteBalance(current: CameraProfile): CameraProfile {
        val conn = connection ?: return current
        conn.run { it.awbOnce() }
        delay(AWB_SETTLE_MS)
        val updated = conn.run { cam ->
            val g = cam.getWhiteBalanceGain()  // [R, G, B], знаковые (-127..127)
            val red = 2.0.pow((g.getOrElse(0) { 0 }) / 64.0).toFloat()
            val blue = 2.0.pow((g.getOrElse(2) { 0 }) / 64.0).toFloat()
            current.copy(
                wbRedGain = red,
                wbGreenEvenGain = 1f,
                wbGreenOddGain = 1f,
                wbBlueGain = blue,
            ).also { ToupcamProfileApplier.apply(cam, it) }
        }
        currentProfile = updated
        return updated
    }


    override suspend fun capture(target: File, lampType: LampType): CameraProfile {
        val profile = currentProfile
        withContext(Dispatchers.IO) {
            val conn = connectionForSnap()
            val still = snapStillBlocking(conn)
            applyFfcIfConfigured(conn, still, profile) // §2.3: нормализация делением на эталон
            // R09: неудачный compress — ошибка съёмки; R08: файл появляется целиком или никак.
            writeAtomically(target) { out ->
                still.compress(Bitmap.CompressFormat.JPEG, profile.jpegQuality.coerceIn(1, 100), out)
            }
        }
        return profile
    }

    /**
     * §2.3: снять эталонный кадр FFC. Делаем полнокадровый снимок белого фона,
     * уменьшаем (усреднение уже подавляет пыль) и слегка размываем — получаем шаблон
     * неравномерности; сохраняем PNG рядом в filesDir (свой на профиль). Путь пишем в профиль.
     */
    override suspend fun captureFfcReference(lampType: LampType): String = withContext(Dispatchers.IO) {
        val still = snapStillBlocking(connectionForSnap())
        val h = (FFC_TEMPLATE_WIDTH.toLong() * still.height / still.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(still, FFC_TEMPLATE_WIDTH, h, true)
        val template = boxBlur(small, FFC_BLUR_RADIUS, FFC_BLUR_PASSES)
        if (template !== small) small.recycle()
        // R11: всегда новый файл — эталон подтверждённого профиля не перезаписывается.
        // Профиль переключится на него только при «Сохранить».
        val file = FfcFiles.newFile(context, lampType)
        writeAtomically(file) { template.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // подхватить свежий шаблон (прежний не освобождаем явно — может использоваться снимком)
        synchronized(this@ToupcamController) {
            cachedTemplate = template; cachedTemplatePath = file.absolutePath
        }
        file.absolutePath
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
    private fun applyFfcIfConfigured(conn: CameraConnection, still: Bitmap, profile: CameraProfile) {
        val path = profile.ffcTemplatePath
        if (path.isBlank()) return
        val tmpl = loadTemplate(path) ?: error(
            "Эталон FFC профиля «${profile.lampType.ruLabel}» не читается. " +
                "Переснимите эталон в «Профилях камеры» и сохраните профиль.",
        )
        // Коррекция работает только с пикселями буферов (хэндл камеры не нужен) — вне очереди.
        check(conn.device.applyFlatField(still, tmpl)) { "Не удалось применить коррекцию FFC к снимку" }
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

    private fun onSdkEvent(dev: ToupcamDevice, event: Int, generation: Long) {
        // Событие прежнего подключения — не относится к текущей камере.
        if (generation != stillGate.currentGeneration()) {
            Log.w(TAG, "Событие 0x${Integer.toHexString(event)} прежнего подключения — проигнорировано")
            return
        }
        when (event) {
            EVENT_IMAGE -> {
                frameEvents++
                val surface = previewSurface
                if (surface == null) {
                    if (frameEvents == 1L || frameEvents % 30 == 0L) {
                        Log.i(TAG, "EVENT_IMAGE #$frameEvents: previewSurface=null (кадр отброшен)")
                    }
                    return
                }
                val bmp = previewBitmap ?: return
                if (frameEvents == 1L || frameEvents % 30 == 0L) {
                    Log.i(TAG, "EVENT_IMAGE #$frameEvents: рисуем $previewSize")
                }
                dev.pullImage(bmp)
                blit(bmp, surface)
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
                canvas.drawBitmap(bmp, null, Rect(0, 0, canvas.width, canvas.height), null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Отрисовка превью: ${t.message}")
        }
    }

    // ---- Подключение USB и размеры ---------------------------------------------



    // ---- Размеры и буферы ---------------------------------------------------------

    private fun readSizes(dev: ToupcamDevice) {
        dev.previewSize()?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let {
            previewSize = Size(it[0], it[1])
        }
        dev.stillSize()?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let {
            stillSize = Size(it[0], it[1])
        }
        Log.i(TAG, "Touptek: preview=$previewSize still=$stillSize")
    }

    private fun ensureStillBitmap(): Bitmap {
        val existing = stillBitmap
        if (existing != null && existing.width == stillSize.width && existing.height == stillSize.height) {
            return existing
        }
        return Bitmap.createBitmap(stillSize.width, stillSize.height, Bitmap.Config.ARGB_8888)
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
