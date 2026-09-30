package com.siams.kern_prototype.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import com.siams.kern_prototype.data.storage.writeAtomically
import com.siams.kern_prototype.data.settings.CameraProfileStore
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Реализация [KernCamera] поверх встроенной камеры телефона (Camera2, §6, §13).
 * Не разбрасывать CaptureRequest.Builder по экранам — всё здесь.
 *
 * Используется как запасной вариант, когда USB-камера Touptek не подключена
 * (основной путь — [ToupcamController]).
 */
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileStore: CameraProfileStore,
) : KernCamera {
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null

    private var previewSize: Size = Size(1920, 1080)
    private var jpegSize: Size = Size(1920, 1080)
    private var sensorOrientation: Int = 90

    private var currentProfile: CameraProfile = CameraProfile.defaultFor(LampType.DAYLIGHT)

    private val _state = MutableStateFlow<CameraControllerState>(CameraControllerState.Idle)
    override val state: StateFlow<CameraControllerState> = _state.asStateFlow()

    val currentPreviewSize: Size get() = previewSize
    var manualSupported: Boolean = false
        private set

    /** Подтвердил ли последний снимок, что AE/AWB/AF реально OFF в CaptureResult (§6.5). */
    var lastCaptureVerified: Boolean = true
        private set

    /** Открыть камеру, выбрать размеры, загрузить профиль под [lampType]. */
    @SuppressLint("MissingPermission")
    override suspend fun open(lampType: LampType) {
        if (device != null) return
        _state.value = CameraControllerState.Opening
        ensureThread()
        try {
            val id = pickCameraId()
            cameraId = id
            val chars = cameraManager.getCameraCharacteristics(id)
            characteristics = chars
            manualSupported = CameraProfileApplier.supportsManual(chars)
            sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            chooseSizes(chars)
            currentProfile = profileStore.read(lampType)

            device = openDevice(id)
            _state.value = CameraControllerState.Ready(previewSize, manualSupported)
        } catch (t: Throwable) {
            _state.value = CameraControllerState.Error(t.message ?: "Ошибка открытия камеры")
            throw t
        }
    }

    /** Создать сессию превью + JPEG и запустить repeating request с текущим профилем. */
    override suspend fun startSession(surface: Surface) {
        val dev = device ?: error("Камера не открыта")
        previewSurface = surface
        val reader = ImageReader.newInstance(
            jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2,
        )
        imageReader = reader

        session?.close()
        session = createSession(dev, listOf(surface, reader.surface))
        startPreview()
    }

    /** Мгновенно применить профиль — пересборка repeating request БЕЗ перезапуска сессии (§6.4). */
    override suspend fun applyProfile(profile: CameraProfile) {
        currentProfile = profile
        startPreview()
    }

    /** Переключить тип освещения: прочитать сохранённый профиль и применить (§6.4). */
    override suspend fun switchLamp(lampType: LampType) {
        applyProfile(profileStore.read(lampType))
    }

    override val activeProfile: CameraProfile get() = currentProfile

    private fun startPreview() {
        val dev = device ?: return
        val session = session ?: return
        val surface = previewSurface ?: return
        val chars = characteristics ?: return
        val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.addTarget(surface)
        CameraProfileApplier.apply(builder, currentProfile, chars)
        session.setRepeatingRequest(builder.build(), null, handler)
    }

    /**
     * Снять JPEG максимального качества в [target] (§6.5). Возвращает применённый
     * профиль (для аудита идентичности). Проверяет отключение AE/AWB/AF в CaptureResult.
     */
    override suspend fun capture(target: File, lampType: LampType): CaptureOutcome = CaptureOutcome(captureCamera2(target), audit = null)

    /** Снимок Camera2 (запасной путь; аудит параметров SDK Touptek здесь не ведётся). */
    private suspend fun captureCamera2(target: File): CameraProfile {
        val dev = device ?: error("Камера не открыта")
        val session = session ?: error("Сессия не создана")
        val reader = imageReader ?: error("ImageReader не создан")
        val chars = characteristics ?: error("Нет характеристик камеры")
        val profile = currentProfile
        // FFC реализован только для Touptek: настроенную коррекцию нельзя молча пропустить.
        check(profile.ffcTemplatePath.isBlank()) {
            "Коррекция FFC не поддерживается камерой телефона, а в профиле задан эталон"
        }

        // Читатель: сохранить кадр в файл.
        val saved = suspendCancellableCoroutine<Unit> { cont ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage()
                try {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    writeAtomically(target) { it.write(bytes); true } // R08
                    if (cont.isActive) cont.resume(Unit)
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t)
                } finally {
                    image.close()
                    r.setOnImageAvailableListener(null, null)
                }
            }, handler)

            val builder = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            CameraProfileApplier.apply(builder, profile, chars)
            builder.set(CaptureRequest.JPEG_QUALITY, profile.jpegQuality.toByte())
            builder.set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)

            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        s: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        verifyManualLock(result)
                    }

                    override fun onCaptureFailed(
                        s: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure,
                    ) {
                        if (cont.isActive) cont.resumeWithException(
                            IllegalStateException("Съёмка не удалась: reason=${failure.reason}"),
                        )
                    }
                },
                handler,
            )
        }
        return profile.also { saved }
    }

    /**
     * Сверка §6.5: реально ли устройство выключило AE/AWB/AF (иначе «побитовая
     * идентичность» не гарантируется). Пишем в лог; результат — в [lastCaptureVerified].
     */
    private fun verifyManualLock(result: TotalCaptureResult) {
        val ae = result.get(CaptureResult.CONTROL_AE_MODE)
        val awb = result.get(CaptureResult.CONTROL_AWB_MODE)
        val af = result.get(CaptureResult.CONTROL_AF_MODE)
        val ok = ae == CameraMetadata.CONTROL_AE_MODE_OFF &&
            awb == CameraMetadata.CONTROL_AWB_MODE_OFF &&
            af == CameraMetadata.CONTROL_AF_MODE_OFF
        lastCaptureVerified = ok
        if (ok) {
            Log.d(TAG, "Ручной режим подтверждён в CaptureResult (AE/AWB/AF = OFF). §6.5")
        } else {
            Log.w(TAG, "Идентичность под вопросом: AE=$ae AWB=$awb AF=$af, ожидалось OFF(0). §6.5")
        }
    }

    override fun release() {
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { imageReader?.close() }
        session = null
        device = null
        imageReader = null
        previewSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
        _state.value = CameraControllerState.Idle
    }

    // ---- private helpers ---------------------------------------------------

    private fun ensureThread() {
        if (thread == null) {
            thread = HandlerThread("kern-camera").also { it.start() }
            handler = Handler(thread!!.looper)
        }
    }

    private fun pickCameraId(): String {
        val ids = cameraManager.cameraIdList
        // Предпочесть заднюю камеру с manual-поддержкой.
        val back = ids.firstOrNull { id ->
            val c = cameraManager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                CameraProfileApplier.supportsManual(c)
        }
        val anyBack = ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
        return back ?: anyBack ?: ids.firstOrNull() ?: error("Камеры не найдены")
    }

    private fun chooseSizes(chars: CameraCharacteristics) {
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val jpeg = map?.getOutputSizes(ImageFormat.JPEG)
        jpegSize = jpeg?.maxByOrNull { it.width.toLong() * it.height } ?: Size(1920, 1080)

        val preview = map?.getOutputSizes(android.graphics.SurfaceTexture::class.java)
        // Превью: наибольшее <= 1920x1080 с тем же соотношением, что и JPEG.
        val targetRatio = jpegSize.width.toDouble() / jpegSize.height
        previewSize = preview
            ?.filter { it.width <= 1920 && it.height <= 1080 }
            ?.minByOrNull { kotlin.math.abs(it.width.toDouble() / it.height - targetRatio) }
            ?: preview?.firstOrNull()
            ?: Size(1920, 1080)
    }

    private suspend fun openDevice(id: String): CameraDevice =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera)
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cont.isActive) cont.resumeWithException(
                        IllegalStateException("Ошибка камеры: код $error"),
                    )
                }
            }, handler)
        }

    @Suppress("DEPRECATION")
    private suspend fun createSession(dev: CameraDevice, surfaces: List<Surface>): CameraCaptureSession =
        suspendCancellableCoroutine { cont ->
            dev.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(
                            IllegalStateException("Не удалось сконфигурировать сессию камеры"),
                        )
                    }
                },
                handler,
            )
        }

    companion object {
        private const val TAG = "CameraController"
    }
}
