package com.siams.kern_prototype.viewmodel

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.KernCamera
import com.siams.kern_prototype.data.settings.CameraProfileStore
import com.siams.kern_prototype.data.settings.ProfileTransfer
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Какая авто-процедура сейчас выполняется (для индикатора прогресса, §2). */
enum class AutoProc { NONE, EXPOSURE, WHITE_BALANCE, FLAT_FIELD }

/**
 * Живой редактор профилей камеры (§6.3, §12/Этап 1). Держит [CameraController],
 * показывает превью и применяет редактируемый профиль МГНОВЕННО (пересборка
 * repeating request без перезапуска сессии). Сохранение — по кнопке.
 */
@HiltViewModel
class CameraProfilesViewModel @Inject constructor(
    private val store: CameraProfileStore,
    private val transfer: ProfileTransfer,
    val controller: KernCamera,
) : ViewModel() {

    private val _lamp = MutableStateFlow(LampType.DAYLIGHT)
    val lamp: StateFlow<LampType> = _lamp.asStateFlow()

    // Редактируемая копия профиля выбранной лампы; применяется к превью на лету.
    private val _working = MutableStateFlow(CameraProfile.defaultFor(LampType.DAYLIGHT))
    val working: StateFlow<CameraProfile> = _working.asStateFlow()

    // Последняя сохранённая версия профиля (для показа даты изменения и решения о предупреждении).
    private val _saved = MutableStateFlow(CameraProfile.defaultFor(LampType.DAYLIGHT))
    val saved: StateFlow<CameraProfile> = _saved.asStateFlow()

    // §4.1: показать предупреждение о несопоставимости снимков перед перезаписью профиля.
    private val _showModifyWarning = MutableStateFlow(false)
    val showModifyWarning: StateFlow<Boolean> = _showModifyWarning.asStateFlow()

    // §2: какая авто-процедура сейчас идёт (для прогресса и блокировки кнопок).
    private val _autoProc = MutableStateFlow(AutoProc.NONE)
    val autoProc: StateFlow<AutoProc> = _autoProc.asStateFlow()

    // §2.3: показать инструкцию перед съёмкой эталона FFC.
    private val _showFfcInstruction = MutableStateFlow(false)
    val showFfcInstruction: StateFlow<Boolean> = _showFfcInstruction.asStateFlow()

    // §4.2: предупреждение о несопоставимости снимков перед импортом профиля.
    private val _showImportWarning = MutableStateFlow(false)
    val showImportWarning: StateFlow<Boolean> = _showImportWarning.asStateFlow()
    private var pendingImportJson: String? = null

    val cameraState: StateFlow<CameraControllerState> = controller.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private var pendingSurface: SurfaceTexture? = null
    private var opened = false

    init {
        viewModelScope.launch {
            val loaded = store.read(_lamp.value)
            _saved.value = loaded
            _working.value = loaded
        }
        // Живое применение: любое изменение рабочего профиля сразу уходит в превью.
        viewModelScope.launch {
            _working.collectLatest { p ->
                runCatching { controller.applyProfile(p) }
            }
        }
    }

    /** Вызвать при входе на экран: подключить USB-камеру Touptek (запрос USB-доступа — внутри). */
    fun start() {
        if (opened) return
        opened = true
        viewModelScope.launch {
            runCatching { controller.open(_lamp.value) }
                .onSuccess { startSessionIfReady() }
                .onFailure { _message.value = it.message }
        }
    }

    fun onSurfaceAvailable(surfaceTexture: SurfaceTexture) {
        pendingSurface = surfaceTexture
        viewModelScope.launch { startSessionIfReady() }
    }

    fun onSurfaceDestroyed() {
        pendingSurface = null
    }

    private suspend fun startSessionIfReady() {
        val st = pendingSurface ?: return
        val state = controller.state.value as? CameraControllerState.Ready ?: return
        st.setDefaultBufferSize(state.previewSize.width, state.previewSize.height)
        runCatching {
            controller.startSession(Surface(st))
            controller.applyProfile(_working.value)
        }.onFailure { _message.value = it.message }
    }

    /** Переключить редактируемый профиль (Дневной/УФ): подгрузить его и показать в превью. */
    fun selectLamp(lampType: LampType) {
        if (_lamp.value == lampType) return
        _lamp.value = lampType
        viewModelScope.launch {
            val loaded = store.read(lampType)
            _saved.value = loaded
            _working.value = loaded
        }
    }

    /** Изменение параметра из UI — обновляем рабочий профиль (превью применит сам). */
    fun update(profile: CameraProfile) {
        _working.value = profile
    }

    /**
     * §2.1: запустить автоэкспозицию. Результат (выдержка+усиление) подставляется в
     * рабочий профиль; пользователь может скорректировать вручную после.
     */
    fun runAutoExposure() {
        if (_autoProc.value != AutoProc.NONE) return
        _autoProc.value = AutoProc.EXPOSURE
        viewModelScope.launch {
            runCatching { controller.autoExposure(_working.value) }
                .onSuccess { _working.value = it; _message.value = "Автоэкспозиция завершена" }
                .onFailure { _message.value = it.message ?: "Ошибка автоэкспозиции" }
            _autoProc.value = AutoProc.NONE
        }
    }

    /** §2.2: запустить автобаланс белого; результат (R/G/B) подставляется в рабочий профиль. */
    fun runAutoWhiteBalance() {
        if (_autoProc.value != AutoProc.NONE) return
        _autoProc.value = AutoProc.WHITE_BALANCE
        viewModelScope.launch {
            runCatching { controller.autoWhiteBalance(_working.value) }
                .onSuccess { _working.value = it; _message.value = "Автобаланс белого завершён" }
                .onFailure { _message.value = it.message ?: "Ошибка автобаланса" }
            _autoProc.value = AutoProc.NONE
        }
    }

    /** §2.3: показать инструкцию (поместить белый фон) перед съёмкой эталона FFC. */
    fun requestFfc() {
        if (_autoProc.value == AutoProc.NONE) _showFfcInstruction.value = true
    }

    fun dismissFfc() {
        _showFfcInstruction.value = false
    }

    /**
     * §2.3: снять эталонный кадр FFC после подтверждения. Путь к шаблону кладём в рабочий
     * профиль (сохранится вместе с профилем); коррекция снимков — внутри [KernCamera.capture].
     */
    fun confirmFfc() {
        _showFfcInstruction.value = false
        if (_autoProc.value != AutoProc.NONE) return
        _autoProc.value = AutoProc.FLAT_FIELD
        viewModelScope.launch {
            runCatching { controller.captureFfcReference(_lamp.value) }
                .onSuccess { path ->
                    if (path != null) {
                        _working.value = _working.value.copy(ffcTemplatePath = path)
                        _message.value = "Эталон FFC снят. Сохраните профиль, чтобы применить."
                    } else {
                        _message.value = "FFC не поддерживается этой камерой"
                    }
                }
                .onFailure { _message.value = it.message ?: "Ошибка съёмки эталона FFC" }
            _autoProc.value = AutoProc.NONE
        }
    }

    /**
     * Запрос на сохранение (§4.1). Если профиль уже был откалиброван ранее
     * (`lastModifiedAt > 0`) — сперва показываем предупреждение о несопоставимости
     * снимков и ждём явного подтверждения. Первое сохранение (из дефолта) — без предупреждения.
     */
    fun requestSave() {
        if (_saved.value.lastModifiedAt > 0L) _showModifyWarning.value = true else confirmSave()
    }

    fun dismissWarning() {
        _showModifyWarning.value = false
    }

    /** Подтверждённое сохранение: пишем профиль с новой отметкой времени. */
    fun confirmSave() {
        _showModifyWarning.value = false
        viewModelScope.launch {
            val toSave = _working.value.copy(lastModifiedAt = System.currentTimeMillis())
            store.save(toSave)
            _saved.value = toSave
            _working.value = toSave
            _message.value = "Профиль «${_lamp.value.ruLabel}» сохранён"
        }
    }

    /** §4.2: собрать JSON со всеми профилями и эталонами FFC (экран запишет его в выбранный файл). */
    suspend fun buildExportJson(): String = transfer.exportJson()

    /** §4.2: файл выбран для импорта — показываем предупреждение перед применением. */
    fun stageImport(json: String) {
        pendingImportJson = json
        _showImportWarning.value = true
    }

    fun dismissImport() {
        pendingImportJson = null
        _showImportWarning.value = false
    }

    /** §4.2: подтверждённый импорт — применить профили и перечитать текущий в редактор. */
    fun confirmImport() {
        val text = pendingImportJson ?: return
        pendingImportJson = null
        _showImportWarning.value = false
        viewModelScope.launch {
            runCatching { transfer.importJson(text) }
                .onSuccess { n ->
                    val loaded = store.read(_lamp.value)
                    _saved.value = loaded
                    _working.value = loaded
                    _message.value = "Импортировано профилей: $n"
                }
                .onFailure { _message.value = it.message ?: "Ошибка импорта профиля" }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        controller.release()
    }
}
