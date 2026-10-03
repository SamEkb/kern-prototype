package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.domain.model.PassportField
import com.siams.kern_prototype.domain.model.PassportSchema
import com.siams.kern_prototype.domain.model.ServerSettings
import com.siams.kern_prototype.network.ConnectionResult
import com.siams.kern_prototype.network.ConnectionTester
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface TestState {
    data object Idle : TestState
    data object Testing : TestState
    data class Success(val message: String) : TestState
    data class Failure(val message: String) : TestState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: ServerSettingsStore,
    private val tester: ConnectionTester,
) : ViewModel() {

    val settings: StateFlow<ServerSettings> = store.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerSettings(),
    )

    private val _test = MutableStateFlow<TestState>(TestState.Idle)
    val test: StateFlow<TestState> = _test.asStateFlow()

    /**
     * Поля паспорта, которые будут использоваться при «загрузке конфигурации».
     * Пока серверного эндпоинта нет (§10.1) — это встроенная гео-схема §8.5.
     */
    val fields: List<PassportField> = PassportSchema.default

    /** R20: ошибка сохранения (например, шифрования пароля) — сообщение, а не падение. */
    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun consumeSaveError() {
        _saveError.value = null
    }

    /**
     * R20: форма экрана → настройки. Сохранённый пароль недоступен, а поле пароля пусто — пароль
     * не введён заново: это НЕ намеренное удаление (секрет/признак не трогаются), вход не выполняется.
     */
    private suspend fun resolveForm(form: ServerSettings): ServerSettings {
        val stored = store.settings.first()
        return form.copy(passwordUnavailable = stored.passwordUnavailable && form.password.isEmpty())
    }

    fun save(form: ServerSettings) {
        viewModelScope.launch {
            runCatching { store.save(resolveForm(form)) }
                .onFailure { _saveError.value = "Настройки не сохранены: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    /**
     * «Загрузить конфигурацию полей» (§4.1). Серверный эндпоинт ещё не готов (§10.1),
     * поэтому загружается встроенная гео-схема §8.5 и фиксируется дата. Переводит форму
     * долбления в полный режим.
     */
    fun loadFieldsConfig() {
        viewModelScope.launch { store.setFieldsLoadedAt(System.currentTimeMillis()) }
    }

    fun testConnection(form: ServerSettings) {
        viewModelScope.launch {
            _test.value = TestState.Testing
            val settings = resolveForm(form)
            when (val r = tester.test(settings)) {
                ConnectionResult.PasswordUnavailable ->
                    _test.value = TestState.Failure("Сохранённый пароль недоступен на этом устройстве — введите его заново.")
                is ConnectionResult.Reachable -> {
                    // R12: доступность ≠ авторизация; положительный критерий входа не согласован (Q10).
                    _test.value = TestState.Success(
                        "Сервер доступен (HTTP ${r.httpCode}), вход не отклонён. Права учётной записи " +
                            "этой проверкой не подтверждаются — они проверяются при первой отправке.",
                    )
                    // §5.1: сохранить и авто-загрузить конфигурацию полей (встроенная схема, §10.1).
                    runCatching {
                        store.save(settings)
                        store.setFieldsLoadedAt(System.currentTimeMillis())
                    }.onFailure { _saveError.value = "Настройки не сохранены: ${it.message ?: it.javaClass.simpleName}" }
                }
                is ConnectionResult.AuthFailed ->
                    _test.value = TestState.Failure(
                        "Не удалось авторизоваться${r.httpCode?.let { " (HTTP $it)" }.orEmpty()}. Проверьте логин/пароль s900.",
                    )
                is ConnectionResult.LoginFailed ->
                    _test.value = TestState.Failure(
                        "Вход не выполнен (HTTP ${r.httpCode}). Проверьте адрес сервера, порт и учётные данные.",
                    )
                is ConnectionResult.ServerError ->
                    _test.value = TestState.Failure("Ошибка сервера (HTTP ${r.httpCode}).")
                is ConnectionResult.Unreachable ->
                    _test.value = TestState.Failure("Сервер недоступен: ${r.message}")
            }
        }
    }
}
