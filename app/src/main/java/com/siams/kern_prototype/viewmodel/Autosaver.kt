package com.siams.kern_prototype.viewmodel

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * R15: автосохранение формы без кнопки «Сохранить» (ТЗ §4/§11). Каждое изменение подаётся в
 * [submit]; записи выполняются строго по одной, промежуточные состояния, накопившиеся за время
 * записи, пропускаются — записывается последнее. Ошибка записи не останавливает автосохранение —
 * передаётся в [onError].
 *
 * [flush] (выход с экрана) дожидается записи ПОСЛЕДНЕГО поданного состояния — и того, что подано
 * уже во время ожидания, — и различает итог: записано, ошибка (после одного повтора), таймаут.
 * Версия, запись которой завершилась ошибкой, записанной не считается.
 */
internal class Autosaver<S : Any>(
    scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
    private val persist: suspend (S) -> Unit,
) {
    sealed interface FlushResult {
        data object Saved : FlushResult
        data class Failed(val error: Throwable) : FlushResult
        data object Timeout : FlushResult
    }

    private class Versioned<S>(val version: Long, val value: S)

    /** Итог записи версии [version]: [error] null — записано. */
    private class Outcome(val version: Long, val error: Throwable?)

    private val latest = MutableStateFlow<Versioned<S>?>(null)
    private val handled = MutableStateFlow(Outcome(0, null))
    private var version = 0L

    init {
        scope.launch {
            latest.filterNotNull().collect { v ->
                val error = try {
                    persist(v.value)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    runCatching { Log.w("Autosaver", "Автосохранение не выполнено: ${t.message}") }
                    onError(t)
                    t
                }
                handled.value = Outcome(v.version, error)
            }
        }
    }

    /** Новое состояние формы (главный поток). */
    fun submit(state: S) {
        latest.value = Versioned(++version, state)
    }

    /** Дождаться записи последнего состояния; при ошибке — одна повторная попытка. */
    suspend fun flush(timeoutMs: Long = DEFAULT_FLUSH_TIMEOUT_MS): FlushResult =
        withTimeoutOrNull(timeoutMs) {
            var retried = false
            while (true) {
                val current = latest.value ?: return@withTimeoutOrNull FlushResult.Saved
                val outcome = handled.first { it.version >= current.version }
                if (latest.value !== current) continue // во время ожидания подано новое — ждём его
                val error = outcome.error ?: return@withTimeoutOrNull FlushResult.Saved
                if (retried) return@withTimeoutOrNull FlushResult.Failed(error)
                retried = true
                submit(current.value) // повтор того же состояния
            }
            @Suppress("UNREACHABLE_CODE")
            FlushResult.Saved
        } ?: FlushResult.Timeout

    companion object {
        const val DEFAULT_FLUSH_TIMEOUT_MS = 5_000L
    }
}
