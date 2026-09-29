package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.db.relation.DrillingWithTables
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DrillingListViewModel @Inject constructor(
    private val repository: KernRepository,
    private val syncManager: SyncManager,
) : ViewModel() {

    val drillings: StateFlow<List<DrillingWithTables>> =
        repository.observeDrillings().stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList(),
        )

    /** Занятое фото место (§11); пересчитывается при изменении состава долблений. */
    val usedBytes: StateFlow<Long> =
        repository.observeDrillings()
            .map { repository.usedBytes() }
            .flowOn(Dispatchers.IO)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteDrilling(id) }
    }

    fun send(id: String) {
        viewModelScope.launch {
            repository.setSyncStatus(id, SyncStatus.SENDING, null)
            syncManager.enqueue(id)
        }
    }

    fun sendAll(ids: List<String>) {
        viewModelScope.launch {
            ids.forEach { repository.setSyncStatus(it, SyncStatus.SENDING, null) }
            syncManager.enqueueAll(ids)
        }
    }

    /** Отправить все неотправленные и ошибочные (§5.6). */
    fun sendPending() {
        val ids = drillings.value
            .filter { it.drilling.syncStatus == SyncStatus.NOT_SENT || it.drilling.syncStatus == SyncStatus.ERROR }
            .map { it.drilling.id }
        if (ids.isNotEmpty()) sendAll(ids)
    }

    /** Очистить успешно отправленные (§11). */
    fun clearSent() {
        viewModelScope.launch {
            drillings.value
                .filter { it.drilling.syncStatus == SyncStatus.SENT }
                .forEach { repository.deleteDrilling(it.drilling.id) }
        }
    }
}
