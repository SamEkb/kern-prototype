package com.siams.kern_prototype.viewmodel

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.DepthInput
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.KernRepository.DraftKeys
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TableViewViewModel @Inject constructor(
    private val repository: KernRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val tableId: String = savedStateHandle.get<String>(Routes.ARG_TABLE_ID).orEmpty()

    val table: StateFlow<TableWithLayers?> =
        repository.observeTable(tableId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // true после фактического удаления стола → экран уходит назад (без гонки с очисткой VM).
    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    // Отказ операции (например, удаление во время отправки, R05) — показывается экраном.
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun deleteLayer(layerId: String) {
        delete("Не удалось удалить слой") { repository.deleteLayer(layerId) }
    }

    fun deleteTable() {
        delete("Не удалось удалить стол") {
            repository.deleteTable(tableId)
            _closed.value = true
        }
    }

    /** Отказ удаления — сообщением (R05 и ошибки БД); отмена корутины пробрасывается. */
    private fun delete(failure: String, op: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                op()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                _message.value = e.message ?: failure
            } catch (e: Exception) {
                // Откат гарантирован не для любой ошибки — без обещания «ничего не удалено».
                runCatching { Log.w("TableViewViewModel", failure, e) }
                _message.value = "Не удалось завершить удаление. Проверьте список и повторите попытку."
            }
        }
    }

    fun updateNumber(number: Int) {
        viewModelScope.launch { repository.updateTableNumber(tableId, number) }
    }

    // ---- Глубины стола (R15) -----------------------------------------------------------
    //
    // Автосохранение (ТЗ §4/§11, без кнопки «OK»): корректные глубины, отличные от записанных,
    // сразу пишутся во все слои стола; неверный ввод — черновиком ([DraftKeys.depth]) с ошибками у
    // полей. Черновик восстанавливается при открытии. Выход и «Переснять» дожидаются записи.
    // Расхождение глубин слоёв (старые данные) исправляет оператор кнопкой [applyDepthToAllLayers].

    enum class DepthStatus { SAVED, INVALID }

    /** Текст полей глубин ровно как введён; ошибки — у полей. */
    data class DepthState(
        val upper: String = "",
        val lower: String = "",
        val errors: Map<DepthInput.Field, String> = emptyMap(),
        val loaded: Boolean = false,
        val status: DepthStatus = DepthStatus.SAVED,
        val restoredDraft: Boolean = false,
    )

    private val draftKey = DraftKeys.depth(tableId)

    private val _depth = MutableStateFlow(DepthState())
    val depth: StateFlow<DepthState> = _depth.asStateFlow()

    /** Слои стола расходятся по глубинам (данные до R15). */
    val depthMismatch: StateFlow<Boolean> = table
        .map { t -> t != null && DepthInput.mismatch(t.layers) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Записанные глубины стола — с ними сравнивается ввод. */
    private var persistedDepth: Pair<Double?, Double?>? = null

    private data class DepthText(val upper: String, val lower: String, val force: Boolean = false)

    /** Идёт выход/«Переснять»: ввод глубин и повторные действия заблокированы. */
    private val _depthBusy = MutableStateFlow(false)
    val depthBusy: StateFlow<Boolean> = _depthBusy.asStateFlow()

    /** Предел ожидания записи при выходе (выделен для тестов). */
    internal var flushTimeoutMs: Long = Autosaver.DEFAULT_FLUSH_TIMEOUT_MS

    private val depthSaver = Autosaver<DepthText>(viewModelScope, onError = { _message.value = it.message }) { persistDepth(it) }

    init {
        viewModelScope.launch {
            val t = table.filterNotNull().first()
            val stored = DepthInput.tableDepths(t.layers)
            persistedDepth = stored
            val draft = repository.readDraft(draftKey)
            if (!_depth.value.loaded) {
                val upper = draft?.get(DRAFT_UPPER) ?: stored.first?.let(PassportCodec::formatDecimal).orEmpty()
                val lower = draft?.get(DRAFT_LOWER) ?: stored.second?.let(PassportCodec::formatDecimal).orEmpty()
                val errors = if (draft != null) DepthInput.check(upper, lower).errors else emptyMap()
                _depth.value = DepthState(
                    upper = upper, lower = lower, errors = errors, loaded = true,
                    status = if (errors.isEmpty()) DepthStatus.SAVED else DepthStatus.INVALID,
                    restoredDraft = draft != null,
                )
            }
        }
    }

    fun onDepthChange(field: DepthInput.Field, text: String) {
        if (_depthBusy.value) return // выход уже идёт — ввод заблокирован
        val d = _depth.value
        val next = when (field) {
            DepthInput.Field.UPPER -> d.copy(upper = text)
            DepthInput.Field.LOWER -> d.copy(lower = text)
        }.copy(errors = d.errors - field, loaded = true)
        _depth.value = next
        depthSaver.submit(DepthText(next.upper, next.lower))
    }

    /** Расхождение слоёв: записать текущие (корректные) глубины во все слои стола. */
    fun applyDepthToAllLayers() {
        if (_depthBusy.value) return
        val d = _depth.value
        depthSaver.submit(DepthText(d.upper, d.lower, force = true))
    }

    /** Отменить несохранённый ввод глубин: вернуть записанные и удалить черновик. */
    fun discardDepthDraft() {
        if (_depthBusy.value) return
        val (u, l) = persistedDepth ?: (null to null)
        val upper = u?.let(PassportCodec::formatDecimal).orEmpty()
        val lower = l?.let(PassportCodec::formatDecimal).orEmpty()
        _depth.value = DepthState(upper = upper, lower = lower, loaded = true)
        depthSaver.submit(DepthText(upper, lower))
    }

    /**
     * Выход с экрана ([leaving]) / «Переснять»: дождаться записи глубин, затем [onDone]. Ввод на это время
     * заблокирован. Ошибка записи или таймаут — остаёмся на экране с сообщением (новый слой не
     * должен получить старые глубины); повторное нажатие повторяет сохранение.
     */
    fun flush(leaving: Boolean = true, onDone: () -> Unit) {
        if (_depthBusy.value) return
        _depthBusy.value = true
        viewModelScope.launch {
            val r = depthSaver.flush(flushTimeoutMs)
            if (r == Autosaver.FlushResult.Saved) {
                // «Назад»: ввод остаётся заблокированным — экран закрывается. «Переснять» возвращает
                // на этот экран — ввод снова доступен.
                if (!leaving) _depthBusy.value = false
                onDone()
                return@launch
            }
            _message.value = when (r) {
                is Autosaver.FlushResult.Failed -> "Не удалось сохранить глубины: ${r.error.message}. Нажмите ещё раз, чтобы повторить."
                else -> "Сохранение глубин ещё не завершено. Нажмите ещё раз, чтобы повторить."
            }
            _depthBusy.value = false
        }
    }

    /** Запись одного состояния глубин (строго по одному — см. [Autosaver]). */
    private suspend fun persistDepth(text: DepthText) {
        val r = DepthInput.check(text.upper, text.lower)
        if (!r.valid) {
            repository.writeDraft(draftKey, mapOf(DRAFT_UPPER to text.upper, DRAFT_LOWER to text.lower))
            _depth.value = _depth.value.copy(errors = r.errors, status = DepthStatus.INVALID)
            return
        }
        val value = r.upper to r.lower
        if (text.force || value != persistedDepth) {
            // Глубины и удаление черновика — одной транзакцией.
            repository.setLayerDepth(tableId, r.upper, r.lower, clearDraftKey = draftKey)
            persistedDepth = value
        } else {
            repository.clearDraft(draftKey)
        }
        _depth.value = _depth.value.copy(errors = emptyMap(), status = DepthStatus.SAVED)
    }

    private companion object {
        const val DRAFT_UPPER = "upper"
        const val DRAFT_LOWER = "lower"
    }
}
