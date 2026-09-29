package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.db.entity.DrillingEntity
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
class TablesViewModel @Inject constructor(
    private val repository: KernRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val drillingId: String = savedStateHandle.get<String>(Routes.ARG_DRILLING_ID).orEmpty()

    val drilling: StateFlow<DrillingEntity?> =
        repository.observeDrilling(drillingId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val tables: StateFlow<List<TableWithLayers>> =
        repository.observeTables(drillingId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Отказ операции (например, удаление во время отправки, R05) — показывается экраном.
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun deleteTable(tableId: String) {
        viewModelScope.launch {
            try {
                repository.deleteTable(tableId)
            } catch (e: IllegalStateException) {
                _message.value = e.message
            }
        }
    }
}
