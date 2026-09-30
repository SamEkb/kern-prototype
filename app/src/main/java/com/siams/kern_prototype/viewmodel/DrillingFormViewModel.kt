package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.KernRepository.DraftKeys
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

/**
 * Форма паспорта долбления.
 *
 * R15 — автосохранение (ТЗ §4/§11: без кнопки «Сохранить»):
 *  - правка существующего долбления: каждое изменение — если форма корректна и отличается от
 *    записанного — сразу пишется в долбление, черновик удаляется; если неверна — ввод пишется
 *    черновиком ([DraftKeys.passport]), ошибки у полей. Выход с экрана дожидается записи.
 *  - новое долбление: создаётся кнопкой «Создать» (из неё — переход в съёмку); ввод до создания
 *    хранится черновиком.
 *  - при открытии черновик восстанавливается (переживает «Назад» и перезапуск процесса);
 *    «Отменить изменения» возвращает записанные значения.
 */
@HiltViewModel
class DrillingFormViewModel @Inject constructor(
    private val repository: KernRepository,
    settingsStore: ServerSettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val drillingId: String? =
        savedStateHandle.get<String>(Routes.ARG_DRILLING_ID)?.ifBlank { null }
    val isEditing: Boolean = drillingId != null
    private val draftKey = DraftKeys.passport(drillingId)

    /**
     * Режим формы (§3.2/§3.3): если конфигурация полей загружена — полная гео-схема §8.5,
     * иначе упрощённый режим (только «Название»; остальное дозаполнит s900).
     */
    val schema: StateFlow<List<PassportField>> = settingsStore.settings
        .map { if (it.fieldsConfigLoadedAt > 0L) PassportSchema.default else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Состояние формы: название и поля паспорта ровно как введены (без фильтрации символов). */
    data class Form(val title: String = "", val values: Map<String, String> = emptyMap())

    enum class SaveStatus { SAVED, INVALID, DRAFT }

    private val _form = MutableStateFlow(Form())
    val form: StateFlow<Form> = _form.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Ошибки полей по ключам ([TITLE_KEY] — название). */
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors: StateFlow<Map<String, String>> = _errors.asStateFlow()

    private val _status = MutableStateFlow(SaveStatus.SAVED)
    val status: StateFlow<SaveStatus> = _status.asStateFlow()

    /** Форма открыта с восстановленным черновиком. */
    private val _restoredDraft = MutableStateFlow(false)
    val restoredDraft: StateFlow<Boolean> = _restoredDraft.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Идёт выход с экрана или создание: ввод и повторные действия заблокированы, чтобы изменение,
     * сделанное во время ожидания записи, не потерялось.
     */
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Предел ожидания записи при выходе (выделен для тестов). */
    internal var flushTimeoutMs: Long = Autosaver.DEFAULT_FLUSH_TIMEOUT_MS

    /** Записанное в долбление (правка) — с ним сравнивается ввод. */
    private var persisted: Form? = null

    private val autosaver = Autosaver<Form>(viewModelScope, onError = { _message.value = it.message }) { persist(it) }

    init {
        viewModelScope.launch {
            val stored = drillingId?.let { id ->
                repository.getDrilling(id)?.let { Form(it.title, repository.passportStrings(it.passportJson)) }
            }
            persisted = stored
            val draft = repository.readDraft(draftKey)?.let(::fromDraft)
            // Ввод, сделанный до загрузки, не перезаписывается.
            if (!_loaded.value) {
                _form.value = draft ?: stored ?: Form()
                _restoredDraft.value = draft != null && draft != stored
                if (draft != null) {
                    _errors.value = validate(draft)
                    _status.value = if (_errors.value.isEmpty()) SaveStatus.DRAFT else SaveStatus.INVALID
                }
            }
            _loaded.value = true
        }
    }

    fun onTitleChange(title: String) = change(_form.value.copy(title = title), TITLE_KEY)

    fun onFieldChange(key: String, input: String) = change(_form.value.copy(values = _form.value.values + (key to input)), key)

    private fun change(form: Form, key: String) {
        if (_busy.value) return // выход/создание уже идёт — ввод заблокирован
        _form.value = form
        _loaded.value = true
        clearError(key)
        autosaver.submit(form)
    }

    /** Поле изменено — его прежняя ошибка больше не относится к вводу. */
    fun clearError(key: String) {
        if (key in _errors.value) _errors.value = _errors.value - key
    }

    private fun validate(f: Form): Map<String, String> {
        val errors = repository.passportErrors(f.values).toMutableMap()
        if (f.title.isBlank()) errors[TITLE_KEY] = "Укажите название"
        return errors
    }

    /** Запись одного состояния формы (строго по одному — см. [Autosaver]). */
    private suspend fun persist(f: Form) {
        val errors = validate(f)
        _errors.value = errors
        val id = drillingId
        if (id == null) {
            // Новое долбление создаётся кнопкой — до неё ввод хранится черновиком.
            if (f.title.isBlank() && f.values.values.all { it.isBlank() }) repository.clearDraft(draftKey)
            else repository.writeDraft(draftKey, toDraft(f))
            _status.value = if (errors.isEmpty()) SaveStatus.DRAFT else SaveStatus.INVALID
            return
        }
        if (errors.isNotEmpty()) {
            repository.writeDraft(draftKey, toDraft(f))
            _status.value = SaveStatus.INVALID
            return
        }
        if (f != persisted) {
            // Правка и удаление черновика — одной транзакцией (сбой не оставит устаревший черновик).
            repository.updateDrilling(id, f.title, f.values, clearDraftKey = draftKey)
            persisted = f
        } else {
            repository.clearDraft(draftKey)
        }
        _status.value = SaveStatus.SAVED
    }

    /**
     * Выход с экрана: дождаться записи последнего изменения, затем [onDone]. Ввод на это время
     * заблокирован. Ошибка записи или таймаут — остаёмся на экране с сообщением; повторное
     * нажатие повторяет сохранение.
     */
    fun flush(onDone: () -> Unit) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            when (val r = autosaver.flush(flushTimeoutMs)) {
                Autosaver.FlushResult.Saved -> onDone() // ввод остаётся заблокированным — экран закрывается
                is Autosaver.FlushResult.Failed -> {
                    _message.value = "Не удалось сохранить: ${r.error.message}. Нажмите ещё раз, чтобы повторить."
                    _busy.value = false
                }
                Autosaver.FlushResult.Timeout -> {
                    _message.value = "Сохранение ещё не завершено. Нажмите ещё раз, чтобы повторить."
                    _busy.value = false
                }
            }
        }
    }

    /** Отменить несохранённые изменения: вернуть записанные значения и удалить черновик. */
    fun discardDraft() {
        if (_busy.value) return
        val stored = persisted ?: Form()
        _form.value = stored
        _errors.value = emptyMap()
        _restoredDraft.value = false
        autosaver.submit(stored)
    }

    /**
     * Создать новое долбление; в колбэк приходит его id (переход в съёмку).
     * R15: при неверных полях ничего не создаётся — ошибки у полей.
     */
    fun create(onDone: (String) -> Unit) {
        if (drillingId != null || _busy.value) return
        val f = _form.value
        val errors = validate(f)
        _errors.value = errors
        if (errors.isNotEmpty()) return
        _busy.value = true // ввод заблокирован: создаётся ровно проверенная форма [f]
        viewModelScope.launch {
            try {
                // Запись черновика, поданная до нажатия, должна ЗАВЕРШИТЬСЯ до создания: иначе
                // запоздалая запись восстановила бы черновик, удалённый созданием.
                when (val r = autosaver.flush(flushTimeoutMs)) {
                    Autosaver.FlushResult.Saved -> Unit
                    is Autosaver.FlushResult.Failed -> {
                        _message.value = "Не удалось сохранить ввод: ${r.error.message}. Нажмите «Создать» ещё раз."
                        _busy.value = false
                        return@launch
                    }
                    Autosaver.FlushResult.Timeout -> {
                        _message.value = "Сохранение ввода ещё не завершено. Нажмите «Создать» ещё раз."
                        _busy.value = false
                        return@launch
                    }
                }
                // Долбление и удаление черновика — одной транзакцией.
                val id = repository.createDrilling(f.title, f.values, clearDraftKey = draftKey)
                onDone(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                _message.value = "Не удалось создать долбление: ${t.message}"
                _busy.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun toDraft(f: Form): Map<String, String> = f.values + (TITLE_KEY to f.title)

    private fun fromDraft(m: Map<String, String>): Form = Form(m[TITLE_KEY].orEmpty(), m - TITLE_KEY)

    companion object {
        /** Ключ названия в ошибках и черновике (ключи паспорта с «__» не начинаются). */
        const val TITLE_KEY = "__title"
    }
}
