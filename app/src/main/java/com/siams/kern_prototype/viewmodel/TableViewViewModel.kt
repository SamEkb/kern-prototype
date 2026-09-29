package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun saveDepth(upper: Double?, lower: Double?) {
        viewModelScope.launch { repository.setLayerDepth(tableId, upper, lower) }
    }
}
