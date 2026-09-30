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

    /**
     * R15: текст полей паспорта ровно как введён (без фильтрации символов) — проверяется и
     * сохраняется именно он: «12а» не превращается в 12, а остаётся видимой ошибкой.
     */
    private val _values = MutableStateFlow<Map<String, String>>(emptyMap())
    val values: StateFlow<Map<String, String>> = _values.asStateFlow()

    fun onFieldChange(key: String, input: String) {
        _values.value = _values.value + (key to input)
        clearError(key)
    }

    init {
        if (drillingId != null) {
            viewModelScope.launch {
                val d = repository.getDrilling(drillingId)
                if (d != null) {
                    _values.value = repository.passportStrings(d.passportJson)
                    _initial.value = DrillingFormInitial(
                        title = d.title,
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

    /** R15: ошибки полей паспорта по ключам (показываются под полями; сохранение не выполняется). */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    /** Поле изменено — его прежняя ошибка больше не относится к вводу. */
    fun clearError(key: String) {
        if (key in _errors.value) _errors.value = _errors.value - key
    }

    /**
     * Сохранить; в колбэк приходит id долбления (для перехода в съёмку при создании).
     * R15: при неверных полях ничего не сохраняется — ошибки показываются у полей.
     */
    fun save(title: String, onDone: (String) -> Unit) {
        val values = _values.value
        val errors = repository.passportErrors(values)
        _errors.value = errors
        if (errors.isNotEmpty()) return
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
