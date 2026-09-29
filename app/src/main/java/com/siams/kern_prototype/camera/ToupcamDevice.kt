package com.siams.kern_prototype.camera

import android.graphics.Bitmap

/**
 * Нативный доступ к камере Touptek для [ToupcamController]. Боевая реализация —
 * [CamLibDevice] (ToupCam SDK через JNI-мост `CamLib`); интерфейс выделен, чтобы логику
 * контроллера (подключение, события, восстановление) можно было проверить без камеры.
 *
 * Один экземпляр — одно подключение. Все методы, кроме [requestAccess], обращаются к нативному
 * хэндлу камеры и вызываются ТОЛЬКО в очереди подключения ([CameraConnection]) — последовательно
 * и никогда после [close].
 */
internal interface ToupcamDevice {
    /** Найти камеру и получить USB-доступ (может ждать ответа пользователя). Хэндл SDK не трогает. */
    suspend fun requestAccess()

    /** Открыть камеру и запустить поток кадров (без ожиданий). События SDK — в [listener]. */
    fun open(listener: (Int) -> Unit)

    /** Перестать доставлять события SDK (новые события отбрасываются). */
    fun detachListener()

    /** Закрыть камеру (Toupcam_Close). Только после завершения обработчиков событий. */
    fun close()

    fun previewSize(): IntArray?
    fun stillSize(): IntArray?

    /** Запустить снимок; false — SDK отклонил Snap (события не будет). */
    fun snap(): Boolean
    fun pullImage(bitmap: Bitmap)
    /** false — SDK не отдал кадр, bitmap не изменён. */
    fun pullStillImage(bitmap: Bitmap): Boolean
    /**
     * false — коррекция не выполнена. Исключение из правила очереди: работает только с пикселями
     * буферов и хэндл камеры не использует — выполняется в потоке снимка.
     */
    fun applyFlatField(still: Bitmap, template: Bitmap): Boolean

    // Параметры профиля и авто-процедуры (§2, §6) — без изменений относительно CamLib.
    fun setAutoExpoEnable(mode: Int)
    fun setExpoTime(microseconds: Int)
    fun setExpoAGain(percent: Int)
    fun setWhiteBalanceGain(r: Int, g: Int, b: Int)
    fun setGamma(gamma: Int)
    fun setMaxAutoExpoTimeAGain(maxTimeUs: Int, maxGainPercent: Int)
    fun getExpoTime(): Int
    fun getExpoAGain(): Int
    fun awbOnce()
    fun getWhiteBalanceGain(): IntArray
}
