package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.domain.model.PassportField
import com.siams.kern_prototype.domain.model.PassportSchema
import com.siams.kern_prototype.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DrillingFormInitial(
    val title: String = "",
    val values: Map<String, String> = emptyMap(),
    val loaded: Boolean = false,
)

@HiltViewModel
class DrillingFormViewModel @Inject constructor(
    private val repository: KernRepository,
    settingsStore: ServerSettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val drillingId: String? =
        savedStateHandle.get<String>(Routes.ARG_DRILLING_ID)?.ifBlank { null }
    val isEditing: Boolean = drillingId != null

    /**
     * Режим формы (§3.2/§3.3): если конфигурация полей загружена — полная гео-схема §8.5,
     * иначе упрощённый режим (только «Название»; остальное дозаполнит s900).
     */
    val schema: StateFlow<List<PassportField>> = settingsStore.settings
        .map { if (it.fieldsConfigLoadedAt > 0L) PassportSchema.default else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _initial = MutableStateFlow(DrillingFormInitial())
    val initial: StateFlow<DrillingFormInitial> = _initial.asStateFlow()

    init {
        if (drillingId != null) {
            viewModelScope.launch {
                val d = repository.getDrilling(drillingId)
                if (d != null) {
                    _initial.value = DrillingFormInitial(
                        title = d.title,
                        values = repository.passportStrings(d.passportJson),
                        loaded = true,
                    )
                } else {
                    _initial.value = DrillingFormInitial(loaded = true)
                }
            }
        } else {
            _initial.value = DrillingFormInitial(loaded = true)
        }
    }

    /** Сохранить; в колбэк приходит id долбления (для перехода в съёмку при создании). */
    fun save(title: String, values: Map<String, String>, onDone: (String) -> Unit) {
        viewModelScope.launch {
            if (drillingId != null) {
                repository.updateDrilling(drillingId, title, values)
                onDone(drillingId)
            } else {
                val id = repository.createDrilling(title, values)
                onDone(id)
            }
        }
    }
}
