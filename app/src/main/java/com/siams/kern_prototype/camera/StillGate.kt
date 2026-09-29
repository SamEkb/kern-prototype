package com.siams.kern_prototype.camera

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Учёт полнокадрового снимка Touptek (R09, FIXES §7, п.3–5 ревью). Без нативных зависимостей.
 *
 * SDK сообщает о готовности снимка событием EVENT_STILLIMAGE без идентификатора операции,
 * поэтому «чей» это кадр, определяется так:
 *  - у каждого подключения своё поколение; события прежнего подключения не принимаются;
 *  - одновременно выполняется не больше одной операции, и она остаётся ЗАНЯТОЙ, пока кадр
 *    читается ([take] → [finish]), а не только до прихода события;
 *  - если ожидание завершилось неопределённо (таймаут, отмена — в том числе во время чтения),
 *    событие или чтение старого Snap ещё может завершиться позже. Поэтому новые снимки
 *    ЗАПРЕЩЕНЫ ([needsRecovery]) до УСПЕШНОГО переподключения ([connected]).
 *
 * @param T буфер, в который читается кадр (передаётся с операцией, а не через общее поле).
 */
internal class StillGate<T> {

    /** Операция снимка; [complete] срабатывает не более одного раза. */
    class Pending<T>(val generation: Long, val target: T, private val onComplete: (Result<Unit>) -> Unit) {
        private val completed = AtomicBoolean(false)
        internal var reading = false

        fun complete(result: Result<Unit>) {
            if (completed.compareAndSet(false, true)) onComplete(result)
        }
    }

    private var generation = 0L
    private var pending: Pending<T>? = null

    /** true — исход прошлого снимка неизвестен или подключение потеряно: сначала переподключение. */
    @get:Synchronized
    var needsRecovery = false
        private set

    /**
     * Начало нового подключения: новое поколение. Признак восстановления НЕ снимается — только
     * [connected]. Вызывать после остановки обработчиков прежнего подключения.
     */
    @Synchronized
    fun startConnection(): Long {
        pending?.complete(Result.failure(IllegalStateException("Камера переподключена во время снимка")))
        pending = null
        return ++generation
    }

    /** Подключение успешно установлено: снимать снова можно. */
    @Synchronized
    fun connected() {
        needsRecovery = false
    }

    /** Подключение потеряно или восстановление не удалось: до [connected] снимать нельзя. */
    @Synchronized
    fun markRecoveryRequired() {
        needsRecovery = true
    }

    @Synchronized
    fun currentGeneration(): Long = generation

    /** Начать снимок в [target]. Ошибка, если нужно восстановление или операция ещё занята. */
    @Synchronized
    fun begin(target: T, complete: (Result<Unit>) -> Unit): Pending<T> {
        check(!needsRecovery) { "Камера требует переподключения после несостоявшегося снимка" }
        check(pending == null) { "Снимок уже выполняется" }
        return Pending(generation, target, complete).also { pending = it }
    }

    /**
     * Событие «снимок готов» от обработчика поколения [eventGeneration]. Возвращает операцию,
     * кадр которой нужно прочитать (она остаётся занятой до [finish]), либо null — событие
     * позднее/чужое/повторное и должно быть проигнорировано.
     */
    @Synchronized
    fun take(eventGeneration: Long): Pending<T>? {
        if (eventGeneration != generation) return null
        val p = pending ?: return null
        if (p.reading) return null
        p.reading = true
        return p
    }

    /** Чтение кадра завершено (успешно или нет): операция освобождается, результат — ожидающему. */
    fun finish(p: Pending<T>, result: Result<Unit>) {
        synchronized(this) { if (pending === p) pending = null }
        p.complete(result)
    }

    /** SDK отклонил Snap синхронно — события не будет, восстановление не нужно. */
    @Synchronized
    fun rejected(p: Pending<T>) {
        if (pending === p && !p.reading) pending = null
    }

    /**
     * Ожидание прервано без результата (таймаут, отмена): событие ещё может прийти или чтение
     * ещё идёт → снимки запрещены до успешного переподключения. Читающая операция остаётся
     * занятой до [finish]. Ничего не делает, если операция уже завершена.
     */
    @Synchronized
    fun abandon(p: Pending<T>) {
        if (pending !== p) return
        needsRecovery = true
        if (!p.reading) pending = null
    }

    /**
     * Ошибка/отключение/освобождение камеры: нужна переподключение; ожидающая (ещё не читаемая)
     * операция завершается ошибкой. Читаемую завершит её обработчик ([finish]).
     */
    @Synchronized
    fun fail(error: Throwable) {
        needsRecovery = true
        val p = pending ?: return
        if (p.reading) return
        pending = null
        p.complete(Result.failure(error))
    }
}
