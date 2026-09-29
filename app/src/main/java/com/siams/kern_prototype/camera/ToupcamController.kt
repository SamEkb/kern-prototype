package com.siams.kern_prototype.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import com.droid.usbcam.CamLib
import com.siams.kern_prototype.data.settings.CameraProfileStore
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.pow

/**
 * Реализация [KernCamera] для внешней USB-камеры **Touptek E3ISPM20000KPA** (§3).
 * Основной путь съёмки: смартфон ↔ камера по USB-OTG через ToupCam SDK (нативный
 * `libtoupcam.so` + мост `libjnicam.so` из `jnicam.cpp` + Java-обёртка
 * [com.droid.usbcam.CamLib]), а не через Camera2 телефона.
 *
 * Последовательность (по официальному демо ToupTek + `toupcam.h`):
 *  1) [open] — найти камеру в [UsbManager] по `CamLib.getModelName(vid,pid)`,
 *     получить USB-доступ, передать file descriptor в `CamLib.OpenDevice("fd-…")`,
 *     нативно выбрать разрешения и запустить pull-режим; применить профиль;
 *  2) [startSession] — кадры EVENT_IMAGE тянем в Bitmap и рисуем на превью-Surface;
 *  3) [capture] — `snap()` полного разрешения → EVENT_STILLIMAGE → Bitmap → JPEG в файл;
 *  4) [applyProfile]/[switchLamp] — мгновенно меняют экспозицию/усиление/WB.
 *
 * §13: вся работа с камерой централизована здесь + в [CamLib]/[ToupcamProfileApplier].
 */
class ToupcamController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileStore: CameraProfileStore,
) : KernCamera {

    private val _state = MutableStateFlow<CameraControllerState>(CameraControllerState.Idle)
    override val state: StateFlow<CameraControllerState> = _state.asStateFlow()

    private var currentProfile: CameraProfile = CameraProfile.defaultFor(LampType.DAYLIGHT)
    override val activeProfile: CameraProfile get() = currentProfile

    private var camera: CamLib? = null
    private var eventThread: HandlerThread? = null
    private var eventHandler: Handler? = null

    private var previewSize = Size(1920, 1080)
    private var stillSize = Size(1920, 1080)
    // Кросс-поточные (пишутся в open()/startSession/capture, читаются в потоке событий SDK) → @Volatile.
    @Volatile private var previewBitmap: Bitmap? = null
    @Volatile private var stillBitmap: Bitmap? = null
    @Volatile private var previewSurface: Surface? = null
    @Volatile private var frameEvents = 0L

    // Кэш загруженного эталона FFC (§2.3), чтобы не декодировать PNG на каждый снимок.
    @Volatile private var cachedTemplate: Bitmap? = null
    @Volatile private var cachedTemplatePath: String? = null

    // Ожидающий снимок: резолвится из потока событий по EVENT_STILLIMAGE.
    @Volatile
    private var pendingStill: ((Result<Unit>) -> Unit)? = null

    override suspend fun open(lampType: LampType) {
        if (camera != null) return
        _state.value = CameraControllerState.Opening
        currentProfile = profileStore.read(lampType)
        try {
            withContext(Dispatchers.IO) {
                val cam = CamLib.getInstance() // грузит libtoupcam.so + libjnicam.so
                cam.init()
                startEventThread(cam)          // Handler для CamLib.OnEvent (до открытия!)
                connectUsb(cam)                // USB-доступ → OpenDevice → StartPull (нативно)
                readSizes(cam)
                ToupcamProfileApplier.apply(cam, currentProfile)
                previewBitmap = Bitmap.createBitmap(previewSize.width, previewSize.height, Bitmap.Config.ARGB_8888)
                camera = cam
            }
            _state.value = CameraControllerState.Ready(previewSize, manualSupported = true)
        } catch (t: Throwable) {
            releaseInternal()
            _state.value = CameraControllerState.Error(t.message ?: "Ошибка подключения камеры Touptek")
            throw t
        }
    }

    override suspend fun startSession(surface: Surface) {
        camera ?: error("Камера Touptek не открыта")
        // Поток кадров уже запущен в open(); достаточно дать поверхность для отрисовки.
        previewSurface = surface
    }

    override suspend fun applyProfile(profile: CameraProfile) {
        currentProfile = profile
        val cam = camera ?: return
        withContext(Dispatchers.IO) { ToupcamProfileApplier.apply(cam, profile) }
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
    override suspend fun autoExposure(current: CameraProfile): CameraProfile = withContext(Dispatchers.IO) {
        val cam = camera ?: return@withContext current
        cam.setMaxAutoExpoTimeAGain(AE_MAX_TIME_US, AE_MAX_GAIN_PERCENT)
        cam.setAutoExpoEnable(1)               // включить авто-AE
        delay(AE_CONVERGE_MS)                  // дать сойтись (~кадры на нашем fps)
        val us = cam.getExpoTime()
        val gainPct = cam.getExpoAGain()
        cam.setAutoExpoEnable(0)               // заморозить — дальше только ручное
        val updated = current.copy(
            exposureTimeNs = us.toLong() * 1_000L,
            iso = gainPct,
        )
        ToupcamProfileApplier.apply(cam, updated)
        currentProfile = updated
        updated
    }

    /**
     * §2.2: автобаланс белого «в одно нажатие». Запускаем one-push AWB, ждём применения,
     * читаем поканальные усиления и переводим их в множители профиля (обратно к
     * [ToupcamProfileApplier.toWbGain]: multiplier = 2^(gain/64)). Зелёный — опорный (1.0).
     */
    override suspend fun autoWhiteBalance(current: CameraProfile): CameraProfile = withContext(Dispatchers.IO) {
        val cam = camera ?: return@withContext current
        cam.awbOnce()
        delay(AWB_SETTLE_MS)
        val g = cam.getWhiteBalanceGain()      // [R, G, B], знаковые (-127..127)
        val red = 2.0.pow((g.getOrElse(0) { 0 }) / 64.0).toFloat()
        val blue = 2.0.pow((g.getOrElse(2) { 0 }) / 64.0).toFloat()
        val updated = current.copy(
            wbRedGain = red,
            wbGreenEvenGain = 1f,
            wbGreenOddGain = 1f,
            wbBlueGain = blue,
        )
        ToupcamProfileApplier.apply(cam, updated)
        currentProfile = updated
        updated
    }

    override suspend fun capture(target: File, lampType: LampType): CameraProfile {
        camera ?: error("Камера Touptek не открыта")
        val profile = currentProfile
        withContext(Dispatchers.IO) {
            val still = snapStillBlocking()
            applyFfcIfConfigured(still, profile)   // §2.3: нормализация делением на эталон
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
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
        camera ?: error("Камера Touptek не открыта")
        val still = snapStillBlocking()
        val h = (FFC_TEMPLATE_WIDTH.toLong() * still.height / still.width).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(still, FFC_TEMPLATE_WIDTH, h, true)
        val template = boxBlur(small, FFC_BLUR_RADIUS, FFC_BLUR_PASSES)
        if (template !== small) small.recycle()
        val file = ffcFile(lampType)
        file.parentFile?.mkdirs()
        file.outputStream().use { template.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // сбросить кэш и подхватить свежий шаблон
        synchronized(this@ToupcamController) {
            cachedTemplate?.recycle(); cachedTemplate = template; cachedTemplatePath = file.absolutePath
        }
        file.absolutePath
    }

    /** Полнокадровый снимок с ожиданием EVENT_STILLIMAGE (§3c). Возвращает [stillBitmap]. */
    private suspend fun snapStillBlocking(): Bitmap {
        val cam = camera ?: error("Камера Touptek не открыта")
        val still = ensureStillBitmap()
        withTimeout(SNAP_TIMEOUT_MS) {
            suspendCancellableCoroutine<Unit> { cont ->
                pendingStill = { result ->
                    if (cont.isActive) result.fold({ cont.resume(Unit) }, { cont.resumeWithException(it) })
                }
                cont.invokeOnCancellation { pendingStill = null }
                runCatching { cam.snap() }.onFailure {
                    pendingStill = null
                    if (cont.isActive) cont.resumeWithException(it)
                }
            }
        }
        return still
    }

    /** §2.3: если у профиля задан эталон — нормализовать снимок делением (нативно, in-place). */
    private fun applyFfcIfConfigured(still: Bitmap, profile: CameraProfile) {
        val path = profile.ffcTemplatePath
        if (path.isBlank()) return
        val tmpl = loadTemplate(path) ?: return
        runCatching { camera?.applyFlatField(still, tmpl) }
    }

    private fun ffcFile(lamp: LampType): File = File(context.filesDir, "ffc/ffc_${lamp.name}.png")

    private fun loadTemplate(path: String): Bitmap? = synchronized(this) {
        if (cachedTemplatePath == path && cachedTemplate?.isRecycled == false) return cachedTemplate
        val bmp = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        cachedTemplate?.recycle()
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
        releaseInternal()
        _state.value = CameraControllerState.Idle
    }

    // ---- События SDK (поток eventThread) ---------------------------------------

    private fun startEventThread(cam: CamLib) {
        val thread = HandlerThread("toupcam-events").also { it.start() }
        val handler = Handler(thread.looper) { msg ->
            if (msg.what == CamLib.MSG_EVENT) onSdkEvent(msg.arg1)
            true
        }
        eventThread = thread
        eventHandler = handler
        cam.setLocalHandler(handler)
    }

    private fun onSdkEvent(event: Int) {
        val cam = camera ?: return
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
                cam.pullImage(bmp)
                blit(bmp, surface)
            }

            EVENT_STILLIMAGE -> {
                val bmp = stillBitmap
                val cb = pendingStill
                pendingStill = null
                if (bmp != null) {
                    cam.pullStillImage(bmp)
                    cb?.invoke(Result.success(Unit))
                } else {
                    cb?.invoke(Result.failure(IllegalStateException("Нет буфера снимка")))
                }
            }

            EVENT_ERROR, EVENT_DISCONNECTED -> {
                _state.value = CameraControllerState.Error("Камера Touptek отключена")
                pendingStill?.invoke(Result.failure(IllegalStateException("Камера отключена во время снимка")))
                pendingStill = null
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

    private suspend fun connectUsb(cam: CamLib) {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull { d ->
            CamLib.getModelName(d.vendorId, d.productId) != null
        } ?: error("USB-камера Touptek не найдена. Подключите её к телефону через USB-OTG.")

        if (!usb.hasPermission(dev)) requestUsbPermission(usb, dev)

        val conn = usb.openDevice(dev) ?: error("Не удалось открыть USB-устройство камеры.")
        try {
            val ok = cam.OpenDevice(dev.vendorId, dev.productId, conn.fileDescriptor)
            if (!ok) error("Не удалось открыть камеру Touptek (OpenDevice).")
        } finally {
            // SDK дублирует fd внутри (как в официальном демо) — соединение можно закрыть.
            runCatching { conn.close() }
        }
    }

    private fun readSizes(cam: CamLib) {
        cam.getPreviewSize()?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let {
            previewSize = Size(it[0], it[1])
        }
        cam.getStillSize()?.takeIf { it.size == 2 && it[0] > 0 && it[1] > 0 }?.let {
            stillSize = Size(it[0], it[1])
        }
        Log.i(TAG, "Touptek: preview=$previewSize still=$stillSize")
    }

    private fun ensureStillBitmap(): Bitmap {
        val existing = stillBitmap
        if (existing != null && existing.width == stillSize.width && existing.height == stillSize.height) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(stillSize.width, stillSize.height, Bitmap.Config.ARGB_8888)
            .also { stillBitmap = it }
    }

    /** Запросить USB-доступ у пользователя и дождаться ответа (§5 манифеста). */
    private suspend fun requestUsbPermission(usb: UsbManager, device: UsbDevice) =
        suspendCancellableCoroutine<Unit> { cont ->
            val action = "${context.packageName}.TOUPCAM_USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    if (!cont.isActive) return
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(SecurityException("Доступ к USB-камере не выдан."))
                    }
                }
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            usb.requestPermission(device, pi)
        }

    private fun releaseInternal() {
        pendingStill?.invoke(Result.failure(IllegalStateException("Камера освобождена")))
        pendingStill = null
        val cam = camera
        camera = null
        previewSurface = null
        runCatching { cam?.setLocalHandler(null) }
        runCatching { cam?.releaseCamera() }
        eventThread?.quitSafely()
        eventThread = null
        eventHandler = null
        runCatching { previewBitmap?.recycle() }
        runCatching { stillBitmap?.recycle() }
        runCatching { cachedTemplate?.recycle() }
        previewBitmap = null
        stillBitmap = null
        cachedTemplate = null
        cachedTemplatePath = null
    }

    companion object {
        private const val TAG = "ToupcamController"

        // Коды событий toupcam.h.
        private const val EVENT_IMAGE = 0x0004
        private const val EVENT_STILLIMAGE = 0x0005
        private const val EVENT_ERROR = 0x0080
        private const val EVENT_DISCONNECTED = 0x0081

        private const val SNAP_TIMEOUT_MS = 15_000L

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
