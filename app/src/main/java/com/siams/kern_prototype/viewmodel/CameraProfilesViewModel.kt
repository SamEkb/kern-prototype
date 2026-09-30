package com.siams.kern_prototype.viewmodel

import android.graphics.SurfaceTexture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.KernCamera
import com.siams.kern_prototype.camera.UsbDeviceEvents
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
 * Живой редактор профилей камеры (§6.3, §12/Этап 1). Показывает превью и применяет
 * редактируемый профиль к камере сразу. Сохранение — по кнопке.
 *
 * R23: у каждой лампы свой черновик. Переключение лампы не теряет несохранённые правки другой;
 * выход с несохранёнными черновиками — только через явное решение ([requestExit]).
 *
 * R13: пока идёт авто-процедура (AE/AWB/FFC), конфликтующие действия — смена лампы, ползунки,
 * сохранение, импорт/экспорт, выход — запрещены здесь (а не только кнопками). Результат процедуры
 * привязан к лампе и ревизии черновика, для которых её запустили: устаревший отбрасывается.
 * Процедуры запускаются только при готовой камере.
 */
@HiltViewModel
class CameraProfilesViewModel @Inject constructor(
    private val store: CameraProfileStore,
    private val transfer: ProfileTransfer,
    val controller: KernCamera,
    usbEvents: UsbDeviceEvents,
) : ViewModel() {

    private val _lamp = MutableStateFlow(LampType.DAYLIGHT)
    val lamp: StateFlow<LampType> = _lamp.asStateFlow()

    // Черновики и сохранённые версии по лампам (R23). null — ещё не загружены.
    private val drafts = mutableMapOf<LampType, CameraProfile>()
    private val savedByLamp = mutableMapOf<LampType, CameraProfile>()

    /** Ревизия черновика каждой лампы: растёт при любом его изменении (R13 — устаревшие результаты). */
    private val revisions = mutableMapOf<LampType, Long>()

    // Черновик выбранной лампы; применяется к превью на лету.
    private val _working = MutableStateFlow(CameraProfile.defaultFor(LampType.DAYLIGHT))
    val working: StateFlow<CameraProfile> = _working.asStateFlow()

    // Сохранённая версия профиля выбранной лампы (дата изменения и решение о предупреждении).
    private val _saved = MutableStateFlow(CameraProfile.defaultFor(LampType.DAYLIGHT))
    val saved: StateFlow<CameraProfile> = _saved.asStateFlow()

    /**
     * Лампа, чей профиль загружен и показан (null — идёт загрузка). До загрузки правки, процедуры
     * и сохранение не принимаются: иначе правка делалась бы от значений по умолчанию.
     */
    private val _loaded = MutableStateFlow<LampType?>(null)
    val loaded: StateFlow<LampType?> = _loaded.asStateFlow()

    /** Лампы с несохранёнными изменениями (R23). */
    private val _dirty = MutableStateFlow<Set<LampType>>(emptySet())
    val dirty: StateFlow<Set<LampType>> = _dirty.asStateFlow()

    // §4.1: показать предупреждение о несопоставимости снимков перед перезаписью профиля.
    private val _showModifyWarning = MutableStateFlow(false)
    val showModifyWarning: StateFlow<Boolean> = _showModifyWarning.asStateFlow()

    // §2: какая авто-процедура сейчас идёт (для прогресса и блокировки).
    private val _autoProc = MutableStateFlow(AutoProc.NONE)
    val autoProc: StateFlow<AutoProc> = _autoProc.asStateFlow()

    // §2.3: показать инструкцию перед съёмкой эталона FFC.
    private val _showFfcInstruction = MutableStateFlow(false)
    val showFfcInstruction: StateFlow<Boolean> = _showFfcInstruction.asStateFlow()

    // §4.2: предупреждение о несопоставимости снимков перед импортом профиля.
    private val _showImportWarning = MutableStateFlow(false)
    val showImportWarning: StateFlow<Boolean> = _showImportWarning.asStateFlow()
    private var pendingImportJson: String? = null

    /** R23: запрос выхода при несохранённых черновиках — лампы с изменениями (null — диалога нет). */
    private val _exitPrompt = MutableStateFlow<Set<LampType>?>(null)
    val exitPrompt: StateFlow<Set<LampType>?> = _exitPrompt.asStateFlow()

    /** Экрану можно закрыться (решение о черновиках принято или их нет). */
    private val _exit = MutableStateFlow(false)
    val exit: StateFlow<Boolean> = _exit.asStateFlow()

    val cameraState: StateFlow<CameraControllerState> = controller.state

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // R16: подключение, его повтор, поверхность превью, восстановление по USB.
    private val session = CameraScreenSession(
        controller, viewModelScope, usbEvents,
        lamp = { _lamp.value },
        onMessage = { _message.value = it },
        afterSession = { runCatching { controller.applyProfile(_working.value) } },
    )

    /** Идёт авто-процедура — конфликтующие действия запрещены (R13). */
    val isBusy: Boolean get() = _autoProc.value != AutoProc.NONE

    init {
        viewModelScope.launch { showLamp(_lamp.value) }
        // Живое применение: любое изменение рабочего профиля сразу уходит в превью.
        viewModelScope.launch {
            _working.collectLatest { p ->
                runCatching { controller.applyProfile(p) }
            }
        }
    }

    /** Вызвать при входе на экран: подключить USB-камеру Touptek (запрос USB-доступа — внутри). */
    fun start() = session.start()

    /** R16: «Переподключить» после ошибки/отключения камеры. */
    fun reconnect() = session.reconnect()

    fun onSurfaceAvailable(surfaceTexture: SurfaceTexture) = session.onSurfaceAvailable(surfaceTexture)

    fun onSurfaceDestroyed(surfaceTexture: SurfaceTexture): Boolean = session.onSurfaceDestroyed(surfaceTexture)

    /** R16: экран ушёл в фон / вернулся — поверхность отсоединяется и подключается явно. */
    fun onStop() = session.onStop()

    fun onStart() = session.onStart()

    // ---- Черновики (R23) ----------------------------------------------------------------

    private suspend fun savedOf(lamp: LampType): CameraProfile =
        savedByLamp[lamp] ?: store.read(lamp).also { savedByLamp[lamp] = it }

    /** Показать черновик лампы (или сохранённую версию, если черновика нет). */
    private suspend fun showLamp(lamp: LampType) {
        val saved = savedOf(lamp)
        val draft = drafts[lamp] ?: saved.also { drafts[lamp] = it }
        if (_lamp.value != lamp) return // пока загружали, выбрали другую лампу
        _saved.value = saved
        _working.value = draft
        refreshDirty()
        _loaded.value = lamp
    }

    /** Профиль выбранной лампы загружен (правки, процедуры и сохранение возможны). */
    private fun isLoaded(): Boolean = _loaded.value == _lamp.value && drafts[_lamp.value] != null

    private fun setDraft(lamp: LampType, profile: CameraProfile) {
        drafts[lamp] = profile
        revisions[lamp] = (revisions[lamp] ?: 0) + 1
        if (_lamp.value == lamp) _working.value = profile
        refreshDirty()
    }

    private fun refreshDirty() {
        _dirty.value = drafts.filter { (lamp, draft) -> savedByLamp[lamp]?.let { it != draft } ?: false }.keys.toSet()
    }

    private fun refuseIfBusy(): Boolean {
        if (isBusy) _message.value = "Дождитесь окончания автонастройки"
        return isBusy
    }

    /** Переключить редактируемый профиль. Черновик прежней лампы сохраняется (R23). */
    fun selectLamp(lampType: LampType) {
        if (_lamp.value == lampType || refuseIfBusy()) return
        _loaded.value = null
        _lamp.value = lampType
        viewModelScope.launch { showLamp(lampType) }
    }

    /** Изменение параметра из UI (черновик выбранной лампы; превью применит сам). */
    fun update(profile: CameraProfile) {
        // R13: ползунок во время процедуры/чужая лампа; до загрузки профиля лампы — не от чего править.
        if (isBusy || profile.lampType != _lamp.value || !isLoaded()) return
        setDraft(profile.lampType, profile)
    }

    // ---- Авто-процедуры (R13) ------------------------------------------------------------

    /**
     * Запустить процедуру [kind] для выбранной лампы. Результат принимается, только если лампа и
     * ревизия её черновика не изменились с запуска; иначе отбрасывается.
     */
    private fun runProc(kind: AutoProc, doneMessage: String, block: suspend (CameraProfile, LampType) -> CameraProfile?) {
        if (refuseIfBusy() || !isLoaded()) return
        if (controller.state.value !is CameraControllerState.Ready) {
            _message.value = "Камера не готова"
            return
        }
        val lamp = _lamp.value
        val base = drafts[lamp] ?: _working.value
        val revision = revisions[lamp] ?: 0
        _autoProc.value = kind
        viewModelScope.launch {
            try {
                runCatching { block(base, lamp) }
                    .onSuccess { result ->
                        when {
                            result == null -> Unit
                            (revisions[lamp] ?: 0) != revision || result.lampType != lamp ->
                                _message.value = "Результат автонастройки устарел и не применён"
                            else -> {
                                setDraft(lamp, result)
                                _message.value = doneMessage
                            }
                        }
                    }
                    .onFailure { _message.value = it.message ?: "Ошибка автонастройки" }
            } finally {
                _autoProc.value = AutoProc.NONE
            }
        }
    }

    /** §2.1: автоэкспозиция; результат (выдержка+усиление) — в черновик лампы запуска. */
    fun runAutoExposure() =
        runProc(AutoProc.EXPOSURE, "Автоэкспозиция завершена") { base, _ -> controller.autoExposure(base) }

    /** §2.2: автобаланс белого; результат (R/G/B) — в черновик лампы запуска. */
    fun runAutoWhiteBalance() =
        runProc(AutoProc.WHITE_BALANCE, "Автобаланс белого завершён") { base, _ -> controller.autoWhiteBalance(base) }

    /** §2.3: показать инструкцию (поместить белый фон) перед съёмкой эталона FFC. */
    fun requestFfc() {
        if (!refuseIfBusy()) _showFfcInstruction.value = true
    }

    fun dismissFfc() {
        _showFfcInstruction.value = false
    }

    /**
     * §2.3: снять эталонный кадр FFC после подтверждения. Путь к шаблону — в черновик ЛАМПЫ ЗАПУСКА
     * (сохранится вместе с профилем); коррекция снимков — внутри [KernCamera.capture].
     */
    fun confirmFfc() {
        _showFfcInstruction.value = false
        runProc(AutoProc.FLAT_FIELD, "Эталон FFC снят. Сохраните профиль, чтобы применить.") { base, lamp ->
            val path = controller.captureFfcReference(lamp)
            if (path == null) {
                _message.value = "FFC не поддерживается этой камерой"
                null
            } else {
                base.copy(ffcTemplatePath = path)
            }
        }
    }

    // ---- Сохранение (§4.1) ---------------------------------------------------------------

    /**
     * Запрос на сохранение черновика выбранной лампы. Если профиль уже был откалиброван ранее
     * (`lastModifiedAt > 0`) — сперва предупреждение о несопоставимости снимков.
     */
    fun requestSave() {
        if (refuseIfBusy() || !isLoaded()) return
        if (_saved.value.lastModifiedAt > 0L) _showModifyWarning.value = true else confirmSave()
    }

    fun dismissWarning() {
        _showModifyWarning.value = false
    }

    /** Подтверждённое сохранение черновика выбранной лампы. */
    fun confirmSave() {
        _showModifyWarning.value = false
        if (refuseIfBusy()) return
        val lamp = _lamp.value
        viewModelScope.launch {
            saveDraft(lamp)
            _message.value = "Профиль «${lamp.ruLabel}» сохранён"
        }
    }

    private suspend fun saveDraft(lamp: LampType) {
        val draft = drafts[lamp] ?: return
        val toSave = draft.copy(lastModifiedAt = System.currentTimeMillis())
        store.save(toSave)
        savedByLamp[lamp] = toSave
        drafts[lamp] = toSave
        if (_lamp.value == lamp) {
            _saved.value = toSave
            _working.value = toSave
        }
        refreshDirty()
    }

    // ---- Выход (R23) ----------------------------------------------------------------------

    /** «Назад»: во время процедуры — нельзя; есть несохранённые черновики — спросить. */
    fun requestExit() {
        if (refuseIfBusy()) return
        val dirty = _dirty.value
        if (dirty.isEmpty()) _exit.value = true else _exitPrompt.value = dirty
    }

    fun cancelExit() {
        _exitPrompt.value = null
    }

    /** Выйти, отбросив несохранённые черновики. */
    fun discardAndExit() {
        _exitPrompt.value = null
        _exit.value = true
    }

    /** Сохранить все несохранённые черновики и выйти. */
    fun saveAllAndExit() {
        val lamps = _exitPrompt.value ?: return
        _exitPrompt.value = null
        viewModelScope.launch {
            runCatching { lamps.forEach { saveDraft(it) } }
                .onSuccess { _exit.value = true }
                .onFailure { _message.value = it.message ?: "Не удалось сохранить профили" }
        }
    }

    // ---- Перенос профилей (§4.2) -----------------------------------------------------------

    /** §4.2: собрать JSON со всеми профилями и эталонами FFC (экран запишет его в выбранный файл). */
    suspend fun buildExportJson(): String {
        check(!isBusy) { "Дождитесь окончания автонастройки" }
        return transfer.exportJson()
    }

    /** §4.2: файл выбран для импорта — показываем предупреждение перед применением. */
    fun stageImport(json: String) {
        if (refuseIfBusy()) return
        pendingImportJson = json
        _showImportWarning.value = true
    }

    fun dismissImport() {
        pendingImportJson = null
        _showImportWarning.value = false
    }

    /** §4.2: подтверждённый импорт — профили заменяются, черновики сбрасываются. */
    fun confirmImport() {
        val text = pendingImportJson ?: return
        pendingImportJson = null
        _showImportWarning.value = false
        if (refuseIfBusy()) return
        viewModelScope.launch {
            runCatching { transfer.importJson(text) }
                .onSuccess { n ->
                    drafts.clear()
                    savedByLamp.clear()
                    LampType.entries.forEach { revisions[it] = (revisions[it] ?: 0) + 1 }
                    showLamp(_lamp.value)
                    refreshDirty()
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
