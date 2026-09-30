package com.siams.kern_prototype.camera

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Общий «процесс» для подменных устройств: журнал, управляемые задержки и проверка инвариантов
 * единственного нативного хэндла. Любое нарушение попадает в [violations].
 */
internal class FakeCameraRig {
    val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val violations: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val failAccess: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    val devices: MutableList<FakeToupcamDevice> = Collections.synchronizedList(mutableListOf())

    @Volatile var autoStill = true
    @Volatile var accessGate: CompletableDeferred<Unit>? = null   // ожидание USB-доступа
    @Volatile var openGate: CountDownLatch? = null                // медленное нативное открытие
    @Volatile var snapGate: CountDownLatch? = null
    @Volatile var setterGate: CountDownLatch? = null
    @Volatile var pullGate: CountDownLatch? = null
    @Volatile var pullImageGate: CountDownLatch? = null         // медленная отрисовка превью
    /** Кадры превью (EVENT_IMAGE) идут, как у живой камеры; false — поток кадров остановлен. */
    @Volatile var previewFrames = true
    @Volatile var frameIntervalMs = 20L
    /** Записывать отправку каждого кадра в журнал («frame<id>»). */
    @Volatile var logFrames = false

    /** Сколько вызовов хэндла выполняется прямо сейчас (во всех устройствах). */
    val inHandle = AtomicInteger(0)
    /** Открытое сейчас устройство (владелец глобального хэндла). */
    @Volatile var openDevice: String? = null

    /** Снимок журнала (перебирать живой журнал нельзя — его дописывают потоки устройства). */
    fun snapshot(): List<String> = synchronized(log) { log.toList() }
    fun has(entry: String) = log.contains(entry)
    fun indexOf(entry: String) = log.indexOf(entry)
    fun waitFor(entry: String, ms: Long = 3000): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (has(entry)) return true; Thread.sleep(5) }
        return false
    }
}

internal class FakeToupcamDevice(private val rig: FakeCameraRig, val id: String) : ToupcamDevice {
    @Volatile var listener: ((Int) -> Unit)? = null
    /** Слушатель, выданный при открытии (для имитации запоздавших событий после detach). */
    @Volatile var originalListener: ((Int) -> Unit)? = null
    @Volatile private var closed = false

    /** Вызов нативного хэндла: не после close, не параллельно с другим вызовом, только владельцем. */
    private fun <T> handle(name: String, block: () -> T): T {
        if (closed) rig.violations += "$name$id после close"
        if (rig.inHandle.incrementAndGet() > 1) rig.violations += "$name$id параллельно с другим вызовом"
        val owner = rig.openDevice
        if (owner != null && owner != id) rig.violations += "$name$id при открытом $owner"
        try {
            return block()
        } finally {
            rig.inHandle.decrementAndGet()
        }
    }

    override suspend fun requestAccess() {
        rig.log += "access$id"
        rig.accessGate?.await()
        if (id in rig.failAccess) error("USB-камера не найдена")
    }

    override fun open(listener: (Int) -> Unit) = handle("open") {
        rig.log += "openStart$id"
        rig.openGate?.await(5, TimeUnit.SECONDS)
        rig.openDevice?.let { rig.violations += "open$id при открытом $it" }
        rig.openDevice = id
        this.listener = listener
        originalListener = listener
        rig.log += "open$id"
        thread(isDaemon = true) {
            while (!closed) {
                if (rig.previewFrames) {
                    if (rig.logFrames) rig.log += "frame$id"
                    this.listener?.invoke(ToupcamController.EVENT_IMAGE)
                }
                Thread.sleep(rig.frameIntervalMs)
            }
        }
        Unit
    }

    override fun detachListener() { listener = null }

    override fun close() = handle("close") {
        closed = true
        if (rig.openDevice == id) rig.openDevice = null
        rig.log += "close$id"
    }

    override fun previewSize() = handle("previewSize") { intArrayOf(8, 6) }
    override fun stillSize() = handle("stillSize") { intArrayOf(8, 6) }

    override fun snap(): Boolean = handle("snap") {
        rig.log += "snapStart$id"
        rig.snapGate?.await(5, TimeUnit.SECONDS)
        rig.log += "snap$id"
        if (rig.autoStill) thread { listener?.invoke(ToupcamController.EVENT_STILLIMAGE) }
        true
    }

    override fun pullImage(bitmap: Bitmap) = handle("pullImage") {
        rig.log += "pullImage$id"
        val gate = rig.pullImageGate ?: return@handle
        rig.log += "pullImageStart$id"
        gate.await(5, TimeUnit.SECONDS)
        rig.log += "pullImageEnd$id"
    }

    override fun pullStillImage(bitmap: Bitmap): Boolean = handle("pull") {
        rig.log += "pullStart$id"
        rig.pullGate?.await(5, TimeUnit.SECONDS)
        bitmap.eraseColor(0xFF808080.toInt())
        rig.log += "pullEnd$id"
        true
    }

    override fun applyFlatField(still: Bitmap, template: Bitmap) = true // без хэндла

    override fun setAutoExpoEnable(mode: Int) = handle("setAutoExpoEnable") { rig.log += "ae$mode$id" }
    override fun setExpoTime(microseconds: Int) = handle("setExpoTime") {
        rig.log += "expo$id=$microseconds"
        rig.log += "setExpoStart$id"
        rig.setterGate?.await(5, TimeUnit.SECONDS)
        rig.log += "setExpo$id"
    }
    override fun setExpoAGain(percent: Int) = handle("setExpoAGain") {}
    override fun setWhiteBalanceGain(r: Int, g: Int, b: Int) = handle("setWb") {}
    override fun setGamma(gamma: Int) = handle("setGamma") {}
    override fun setMaxAutoExpoTimeAGain(maxTimeUs: Int, maxGainPercent: Int) = handle("setMaxAe") {}
    override fun getExpoTime() = handle("getExpoTime") { 1000 }
    override fun getExpoAGain() = handle("getExpoAGain") { 100 }
    override fun awbOnce() = handle("awbOnce") {}
    override fun getWhiteBalanceGain() = handle("getWb") { intArrayOf(0, 0, 0) }
}
