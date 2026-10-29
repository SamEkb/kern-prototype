package com.siams.kern_prototype.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.droid.usbcam.CamLib
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Боевая реализация [ToupcamDevice]: ToupCam SDK через JNI-мост [CamLib] (§3).
 * Поиск камеры в [UsbManager], USB-доступ (R18) и открытие по file descriptor перенесены
 * из [ToupcamController]; разделены на [requestAccess] (ожидание пользователя, без SDK) и
 * [open] (нативное открытие). Вызовы SDK и их порядок — те же, что и раньше.
 */
internal class CamLibDevice(private val context: Context) : ToupcamDevice {

    private val cam: CamLib = CamLib.getInstance() // грузит libtoupcam.so + libjnicam.so

    private var usbDevice: UsbDevice? = null

    override suspend fun requestAccess() {
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val dev = usb.deviceList.values.firstOrNull { d ->
            CamLib.getModelName(d.vendorId, d.productId) != null
        } ?: error("USB-камера Touptek не найдена. Подключите её к телефону через USB-OTG.")

        if (!usb.hasPermission(dev)) requestUsbPermission(usb, dev)
        usbDevice = dev
    }

    override fun open(listener: (Int) -> Unit) {
        val dev = checkNotNull(usbDevice) { "Нет USB-доступа к камере" }
        cam.init()
        cam.setEventListener { listener(it) } // до открытия!
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val conn = usb.openDevice(dev) ?: error("Не удалось открыть USB-устройство камеры.")
        try {
            val ok = cam.OpenDevice(dev.vendorId, dev.productId, conn.fileDescriptor) // → StartPull (нативно)
            if (!ok) error("Не удалось открыть камеру Touptek (OpenDevice).")
        } finally {
            // SDK дублирует fd внутри (как в официальном демо) — соединение можно закрыть.
            runCatching { conn.close() }
        }
    }

    override fun detachListener() = cam.setEventListener(null)

    override fun close() = cam.releaseCamera()

    override fun previewSize(): IntArray? = cam.getPreviewSize()
    override fun stillSize(): IntArray? = cam.getStillSize()
    override fun snap(): Boolean = cam.snap()
    override fun pullImage(bitmap: Bitmap) = cam.pullImage(bitmap)
    override fun pullStillImage(bitmap: Bitmap): Boolean = cam.pullStillImage(bitmap)
    override fun applyFlatField(still: Bitmap, template: Bitmap): Boolean = cam.applyFlatField(still, template)

    override fun setAutoExpoEnable(mode: Int) = cam.setAutoExpoEnable(mode)
    override fun setExpoTime(microseconds: Int) = cam.setExpoTime(microseconds)
    override fun setExpoAGain(percent: Int) = cam.setExpoAGain(percent)
    override fun setWhiteBalanceGain(r: Int, g: Int, b: Int) = cam.setWhiteBalanceGain(r, g, b)
    override fun setGamma(gamma: Int) = cam.setGamma(gamma)
    override fun setMaxAutoExpoTimeAGain(maxTimeUs: Int, maxGainPercent: Int) =
        cam.setMaxAutoExpoTimeAGain(maxTimeUs, maxGainPercent)
    override fun getExpoTime(): Int = cam.getExpoTime()
    override fun getExpoAGain(): Int = cam.getExpoAGain()
    override fun awbOnce() = cam.awbOnce()
    override fun getWhiteBalanceGain(): IntArray = cam.getWhiteBalanceGain()
    override fun readState(): IntArray = cam.readState()
    override fun describe(): String = cam.describe()
    override fun wbOpenMethod(): Int = cam.getWbOpenMethod()

    /** Запросить USB-доступ у пользователя и дождаться ответа (§5 манифеста). */
    private suspend fun requestUsbPermission(usb: UsbManager, device: UsbDevice) =
        suspendCancellableCoroutine<Unit> { cont ->
            val action = "${context.packageName}.TOUPCAM_USB_PERMISSION"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { context.unregisterReceiver(this) }
                    if (!cont.isActive) return
                    // R18: extras ответа могут не дойти (PendingIntent) — источник истины
                    // hasPermission для запрошенного устройства; extra — только дополнительно.
                    val extraGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val granted = usb.hasPermission(device)
                    Log.i(
                        TAG,
                        "USB-доступ: extras=${intent.extras?.keySet()} extraGranted=$extraGranted hasPermission=$granted",
                    )
                    if (granted) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(SecurityException("Доступ к USB-камере не выдан."))
                    }
                }
            }
            // R18: система дописывает результат (EXTRA_PERMISSION_GRANTED/EXTRA_DEVICE) в Intent
            // через fillIn, что для FLAG_IMMUTABLE не выполняется → нужен FLAG_MUTABLE (API 31+).
            // Intent явный (setPackage), поэтому mutable-PendingIntent допустим и на API 34+.
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val pi = PendingIntent.getBroadcast(context, 0, Intent(action).setPackage(context.packageName), flags)
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            usb.requestPermission(device, pi)
        }

    private companion object {
        const val TAG = "CamLibDevice"
    }
}
