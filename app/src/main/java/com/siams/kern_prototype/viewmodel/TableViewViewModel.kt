package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.DepthInput
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
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
        viewModelScope.launch {
            try {
                repository.deleteLayer(layerId)
            } catch (e: IllegalStateException) {
                _message.value = e.message
            }
        }
    }

    fun deleteTable() {
        viewModelScope.launch {
            try {
                repository.deleteTable(tableId)
                _closed.value = true
            } catch (e: IllegalStateException) {
                _message.value = e.message
            }
        }
    }

    fun updateNumber(number: Int) {
        viewModelScope.launch { repository.updateTableNumber(tableId, number) }
    }

    // ---- Глубины стола (R15) -----------------------------------------------------------

    /** Текст полей глубин ровно как введён; ошибки — у полей; mismatch — слои стола расходятся. */
    data class DepthState(
        val upper: String = "",
        val lower: String = "",
        val errors: Map<DepthInput.Field, String> = emptyMap(),
        val loaded: Boolean = false,
    )

    private val _depth = MutableStateFlow(DepthState())
    val depth: StateFlow<DepthState> = _depth.asStateFlow()

    /** Слои стола расходятся по глубинам (данные до R15) — «OK» запишет общие. */
    val depthMismatch: StateFlow<Boolean> = table
        .map { t -> t != null && DepthInput.mismatch(t.layers) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    init {
        // Поля заполняются глубинами стола один раз — дальше это ввод пользователя.
        viewModelScope.launch {
            val t = table.filterNotNull().first()
            if (!_depth.value.loaded) {
                val (u, l) = DepthInput.tableDepths(t.layers)
                _depth.value = DepthState(
                    upper = u?.let(PassportCodec::formatDecimal).orEmpty(),
                    lower = l?.let(PassportCodec::formatDecimal).orEmpty(),
                    loaded = true,
                )
            }
        }
    }

    fun onDepthChange(field: DepthInput.Field, text: String) {
        val d = _depth.value
        _depth.value = when (field) {
            DepthInput.Field.UPPER -> d.copy(upper = text)
            DepthInput.Field.LOWER -> d.copy(lower = text)
        }.copy(errors = d.errors - field, loaded = true)
    }

    /** R15: неверные глубины не сохраняются — ошибки у полей; иначе общие глубины всех слоёв стола. */
    fun saveDepth() {
        val d = _depth.value
        val r = DepthInput.check(d.upper, d.lower)
        _depth.value = d.copy(errors = r.errors)
        if (!r.valid) return
        viewModelScope.launch {
            try {
                repository.setLayerDepth(tableId, r.upper, r.lower)
                _message.value = "Глубины сохранены"
            } catch (e: IllegalArgumentException) {
                _message.value = e.message
            }
        }
    }
}
