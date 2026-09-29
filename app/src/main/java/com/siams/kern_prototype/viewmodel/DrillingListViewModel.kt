package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.db.relation.DrillingWithTables
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.domain.model.ServerSettings
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.sync.SyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DrillingListViewModel @Inject constructor(
    private val repository: KernRepository,
    private val syncManager: SyncManager,
    settingsStore: ServerSettingsStore,
) : ViewModel() {

    /** Текущий сервер отправки — для подтверждения исходного сервера записей до v5 (R03). */
    val currentServer: StateFlow<ServerSettings> =
        settingsStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ServerSettings())

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

    // Отказ/итог операции (удаление во время отправки, пропуски при очистке) — показывается экраном.
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun consumeMessage() {
        _message.value = null
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                repository.deleteDrilling(id)
            } catch (e: IllegalStateException) {
                _message.value = e.message
            }
        }
    }

    /**
     * R03: оператор подтверждает, что долбление (отправленное до v5) отправлялось на ТЕКУЩИЙ
     * сервер под текущей учётной записью. После этого возможна обычная повторная отправка.
     */
    fun confirmServer(id: String) {
        viewModelScope.launch {
            val dest = currentServer.value.destination()
            _message.value = if (repository.confirmRemoteDestination(id, dest.key)) {
                "Сервер подтверждён: ${dest.host}:${dest.port} (${dest.login}). Отправьте долбление снова."
            } else {
                "Подтверждение не требуется"
            }
        }
    }

    fun send(id: String) = sendAll(listOf(id))

    fun sendAll(ids: List<String>) {
        viewModelScope.launch { syncManager.send(ids) }
    }

    /** Отправить все неотправленные и ошибочные (§5.6). */
    fun sendPending() {
        val ids = drillings.value
            .filter { it.drilling.syncStatus == SyncStatus.NOT_SENT || it.drilling.syncStatus == SyncStatus.ERROR }
            .map { it.drilling.id }
        if (ids.isNotEmpty()) sendAll(ids)
    }

    /**
     * Очистить успешно отправленные (§11). Список — лишь кандидаты: статус каждого проверяется
     * заново в транзакции удаления; изменённые или снова отправляемые не удаляются.
     */
    fun clearSent() {
        viewModelScope.launch {
            val candidates = drillings.value.filter { it.drilling.syncStatus == SyncStatus.SENT }
            val skipped = candidates.count { !repository.deleteDrillingIfSent(it.drilling.id) }
            if (skipped > 0) {
                _message.value = "Не удалено долблений: $skipped — их статус изменился (изменены или отправляются)"
            }
        }
    }
}
