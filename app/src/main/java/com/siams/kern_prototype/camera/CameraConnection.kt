package com.siams.kern_prototype.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Одно подключение к камере Touptek — единственный владелец нативного хэндла SDK (п.1–3 ревью).
 *
 * - **Владение на процесс.** Хэндл камеры в JNI-мосте один на процесс, поэтому подключение
 *   создаётся только после получения общего разрешения ([open]) и возвращает его лишь после
 *   фактического закрытия камеры. Второй контроллер ждёт, пока первый не закроется целиком.
 * - **Очередь.** Все обращения к хэндлу — открытие, Snap, настройки, авто-процедуры, чтение
 *   кадров (обработчики событий), закрытие — выполняются последовательно в одном потоке
 *   подключения. Каждая операция сначала проверяет, что подключение не закрыто, и дальше не
 *   прерывается: закрытие не может «вклиниться» в операцию.
 * - **Закрытие принадлежит подключению**, а не полю контроллера: [close] можно вызвать в любой
 *   момент (в т.ч. пока ждём USB-доступ или открываем камеру) — камера либо не будет открыта,
 *   либо закроется следующей задачей очереди.
 */
internal class CameraConnection private constructor(
    val device: ToupcamDevice,
    private val onEvent: (ToupcamDevice, Int, Long, Long) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "toupcam-connection") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val closing = AtomicBoolean(false)
    private val closedSignal = CompletableDeferred<Unit>()

    @Volatile
    private var accessJob: Deferred<Unit>? = null

    /** true после [close] — операции с хэндлом больше не выполняются. */
    val isClosed: Boolean get() = closing.get()

    /** Номер последнего события, ПОСТУПИВШЕГО в очередь (присваивается в момент поступления). */
    private var arrivals = 0L

    /** Номер поступления и постановка в очередь — атомарно относительно [arrivalBoundary]. */
    private val arrivalLock = Any()

    /**
     * Граница «после этого момента»: номер последнего поступившего события. Событие с номером
     * больше границы поступило в очередь позже её фиксации; событие с номером не больше границы
     * уже стоит в очереди — задача, поставленная после фиксации, выполнится после его обработки.
     */
    fun arrivalBoundary(): Long = synchronized(arrivalLock) { arrivals }

    /**
     * Событие SDK (поток SDK, не блокирует). Номер и монотонное время поступления ([System.nanoTime])
     * фиксируются здесь, до очереди: задержка очереди не влияет ни на время, ни на принадлежность
     * события. После [close] — отбрасывается, как и ещё не начатые.
     */
    fun post(event: Int) {
        if (closing.get()) return
        val arrivedNs = System.nanoTime()
        synchronized(arrivalLock) {
            val seq = ++arrivals
            try {
                executor.execute { if (!closing.get()) onEvent(device, event, seq, arrivedNs) }
            } catch (_: RejectedExecutionException) {
            }
        }
    }

    /**
     * Получить USB-доступ и открыть камеру. Ожидание доступа прерывается [close]; нативное
     * открытие выполняется в очереди только если подключение ещё не закрыто.
     */
    suspend fun connect() = closedAware {
        withContext(dispatcher) {
            val access = async { device.requestAccess() }
            accessJob = access
            if (closing.get()) access.cancel()
            access.await()
            // Проверка и открытие — без точки приостановки: закрытие не может пройти между ними.
            check(!closing.get()) { "Камера освобождена" }
            device.open(::post)
        }
    }

    /**
     * Выполнить [block] с хэндлом камеры в очереди подключения. Ошибка, если подключение закрыто
     * (в т.ч. закрыто, пока операция ждала очереди).
     */
    suspend fun <T> run(block: (ToupcamDevice) -> T): T = closedAware {
        check(!closing.get()) { "Камера освобождена" }
        withContext(dispatcher) {
            check(!closing.get()) { "Камера освобождена" }
            block(device)
        }
    }

    /**
     * Отмена, вызванная закрытием (прерванное ожидание доступа, отклонённая остановленной очередью
     * задача), — это «камера освобождена», а не отмена вызывающего; её отмена пробрасывается как есть.
     */
    private suspend fun <T> closedAware(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        if (closing.get() && currentCoroutineContext().isActive) throw IllegalStateException("Камера освобождена", e)
        throw e
    }

    /**
     * Закрыть подключение (идемпотентно). Новые операции и события отклоняются; ожидание доступа
     * прерывается; закрытие камеры и [finalAction] выполняются в очереди — после текущей
     * операции. Затем разрешение владения возвращается. Возвращает признак завершения.
     */
    fun close(finalAction: () -> Unit = {}): Deferred<Unit> {
        if (!closing.compareAndSet(false, true)) return closedSignal
        accessJob?.cancel()
        runCatching { device.detachListener() }
        val finish = Runnable {
            try {
                runCatching { device.close() }
                runCatching(finalAction)
            } finally {
                executor.shutdown()
                ownership.release()
                closedSignal.complete(Unit)
            }
        }
        try {
            executor.execute(finish)
        } catch (_: RejectedExecutionException) {
            finish.run()
        }
        return closedSignal
    }

    companion object {
        /** Разрешение на владение камерой: одно на процесс. */
        private val ownership = Semaphore(1)

        /**
         * Стать владельцем камеры и создать подключение. Ждёт, пока предыдущее подключение (любого
         * контроллера) закроется целиком; не дождались за [timeoutMs] — ошибка.
         */
        suspend fun open(
            device: ToupcamDevice,
            timeoutMs: Long,
            onEvent: (ToupcamDevice, Int, Long, Long) -> Unit,
        ): CameraConnection {
            // Разрешение, полученное до неуспешного выхода, обязательно возвращается. Отмена или
            // таймаут могут сработать уже ПОСЛЕ acquire(), на выходе из withTimeout (граница
            // отмены): флаг выставляется сразу после acquire(), без точки приостановки.
            var acquired = false
            try {
                withTimeout(timeoutMs) {
                    ownership.acquire()
                    acquired = true
                    afterAcquireForTest?.invoke()
                }
                return CameraConnection(device, onEvent) // дальше разрешением владеет подключение
            } catch (t: Throwable) {
                if (acquired) ownership.release()
                // Истёк наш таймаут ожидания (вызывающий активен) — камера занята; отмену самого
                // вызывающего (в т.ч. его внешний таймаут) пробрасываем как есть.
                if (t is TimeoutCancellationException && currentCoroutineContext().isActive) {
                    throw IllegalStateException("Камера занята другим подключением. Повторите попытку позже.", t)
                }
                throw t
            }
        }

        /** Точка для тестов границы отмены после получения разрешения; в приложении null. */
        @Volatile
        internal var afterAcquireForTest: (suspend () -> Unit)? = null
    }
}
