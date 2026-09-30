package com.siams.kern_prototype.camera

import android.os.Debug
import android.util.Log
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicInteger

/**
 * R21: диагностика памяти тяжёлых операций камеры (снимок, эталон FFC, сохранение слоя).
 * Ничего не меняет в обработке — только измеряет.
 *
 * Два вида замеров:
 *  - отметки фаз ([mark]) — Java-куча, нативная куча и PSS процесса в известной точке операции;
 *  - фоновый опрос с паузой [intervalMs] между чтениями (реальный интервал = пауза + время
 *    чтения метрик, в т.ч. PSS) — пики между отметками (кратковременный пик внутри вызова
 *    SDK/кодирования иначе не виден). Пик опроса — нижняя оценка истинного пика.
 *
 * Метрики читаются синхронно в потоке вызывающего (конструктор, [mark], [finish] c ожиданием
 * остановки опроса) — вызывать только вне главного потока.
 *
 * Сбой чтения метрики — «неизвестно» (null), а не ноль. Сбой замера не ломает операцию.
 * Пиксели Bitmap (API 26+) лежат в нативной куче; PSS — итог процесса (включая mmap SDK).
 */
class MemoryProbe(
    private val operation: String,
    private val reader: MemoryReader = MemoryReader.System,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val t0 = clock()
    private val phases = mutableListOf<MemoryTrace.Phase>()
    private val lock = Any()
    private var peakJava: Long? = null
    private var peakNative: Long? = null
    private var peakPss: Long? = null
    @Volatile private var running = true
    private var finished: MemoryTrace? = null

    private val sampler = Thread({
        while (running) {
            sample()
            try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
        }
    }, "memory-probe").apply { isDaemon = true; start() }

    init {
        mark("start")
    }

    private fun sample(): MemoryTrace.Phase? {
        val java = runCatching { reader.javaUsedBytes() }.getOrNull()
        val native = runCatching { reader.nativeAllocatedBytes() }.getOrNull()
        val pss = runCatching { reader.pssKb() }.getOrNull()?.takeIf { it > 0 }?.let { it * 1024 }
        synchronized(lock) {
            if (finished != null) return null
            java?.let { peakJava = maxOf(peakJava ?: it, it) }
            native?.let { peakNative = maxOf(peakNative ?: it, it) }
            pss?.let { peakPss = maxOf(peakPss ?: it, it) }
        }
        return MemoryTrace.Phase("", clock() - t0, java, native, pss)
    }

    /** Отметка фазы операции (после [finish] игнорируется). */
    fun mark(name: String) {
        val s = sample() ?: return
        synchronized(lock) { if (finished == null) phases += s.copy(name = name) }
    }

    /**
     * Завершить замер (идемпотентно): остановить опрос, зафиксировать итог и записать в лог.
     * [buffers] — оценки размеров буферов операции (см. [MemoryTrace.buffers]).
     */
    fun finish(outcome: String = "ok", buffers: Map<String, Long> = emptyMap()): MemoryTrace {
        synchronized(lock) { finished?.let { return it } }
        mark("end:$outcome")
        running = false
        sampler.interrupt()
        runCatching { sampler.join(JOIN_MS) }
        val trace = synchronized(lock) {
            finished ?: MemoryTrace(
                operation = operation,
                seq = SEQ.incrementAndGet(),
                javaMaxBytes = runCatching { reader.javaMaxBytes() }.getOrNull(),
                peakJavaBytes = peakJava,
                peakNativeBytes = peakNative,
                peakPssBytes = peakPss,
                phases = phases.toList(),
                buffers = buffers,
            ).also { finished = it }
        }
        runCatching { Log.i(TAG, trace.summary()) }
        return trace
    }

    /** Опрос остановлен (для тестов). */
    internal val samplerStopped: Boolean get() = !sampler.isAlive

    companion object {
        const val TAG = "KernMemory"
        const val DEFAULT_INTERVAL_MS = 25L
        private const val JOIN_MS = 500L

        /** Номер замера в процессе — рост пиков по серии снимков виден по [MemoryTrace.seq]. */
        private val SEQ = AtomicInteger(0)
    }
}

/** Источник метрик памяти (выделен для тестов). */
interface MemoryReader {
    fun javaUsedBytes(): Long
    fun javaMaxBytes(): Long
    fun nativeAllocatedBytes(): Long
    /** PSS процесса, КиБ; 0 — недоступно. */
    fun pssKb(): Long

    object System : MemoryReader {
        override fun javaUsedBytes(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
        override fun javaMaxBytes(): Long = Runtime.getRuntime().maxMemory()
        override fun nativeAllocatedBytes(): Long = Debug.getNativeHeapAllocatedSize()
        override fun pssKb(): Long = Debug.getPss()
    }
}

/**
 * Итог замера памяти операции. Значения в байтах; null — метрика не прочитана.
 * [phases] — отметки фаз по порядку ([Phase.atMs] от начала операции).
 */
@Serializable
data class MemoryTrace(
    val operation: String,
    val seq: Int,
    val javaMaxBytes: Long?,
    val peakJavaBytes: Long?,
    val peakNativeBytes: Long?,
    val peakPssBytes: Long?,
    val phases: List<Phase>,
    /** Оценки размеров буферов операции (не измерения), байты. */
    val buffers: Map<String, Long> = emptyMap(),
) {
    @Serializable
    data class Phase(
        val name: String,
        val atMs: Long,
        val javaBytes: Long?,
        val nativeBytes: Long?,
        val pssBytes: Long?,
    )

    fun summary(): String {
        fun mb(v: Long?) = v?.let { "%.1f".format(it / 1_048_576.0) } ?: "?"
        val ph = phases.joinToString(" | ") { "${it.name}@${it.atMs}ms j=${mb(it.javaBytes)} n=${mb(it.nativeBytes)} pss=${mb(it.pssBytes)}" }
        val buf = if (buffers.isEmpty()) "" else " buffers=" + buffers.entries.joinToString(",") { "${it.key}=${mb(it.value)}" }
        return "$operation #$seq peak: java=${mb(peakJavaBytes)}/${mb(javaMaxBytes)} native=${mb(peakNativeBytes)} " +
            "pss=${mb(peakPssBytes)} МиБ$buf; $ph"
    }
}
