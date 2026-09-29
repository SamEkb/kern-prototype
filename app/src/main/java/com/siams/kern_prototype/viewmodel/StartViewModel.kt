package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Онбординг первого запуска (§3.1): если сервер не настроен, приложение один раз за
 * процесс открывает экран настроек. `configured == null` — состояние ещё грузится.
 */
@HiltViewModel
class StartViewModel @Inject constructor(
    store: ServerSettingsStore,
) : ViewModel() {

    /** Отработал ли уже онбординг в этом процессе (чтобы не повторять при рекомпозициях). */
    var onboarded: Boolean = false

    val configured: StateFlow<Boolean?> = store.settings
        .map { it.isConfigured }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
