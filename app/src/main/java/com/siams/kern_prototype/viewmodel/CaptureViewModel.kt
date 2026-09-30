package com.siams.kern_prototype.viewmodel

import android.graphics.SurfaceTexture
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.KernCamera
import com.siams.kern_prototype.camera.UsbDeviceEvents
import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.ui.nav.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel экрана Съёмки (§5.4). Держит [CameraController], счётчик стола,
 * авто-переключение профилей Дневной⇄УФ (§6.4) и автосохранение снимков (§4).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val repository: KernRepository,
    val controller: KernCamera,
    savedStateHandle: SavedStateHandle,
    usbEvents: UsbDeviceEvents,
) : ViewModel() {

    val drillingId: String = savedStateHandle.get<String>(Routes.ARG_DRILLING_ID).orEmpty()
    private val argTableId: String? = savedStateHandle.get<String>(Routes.ARG_TABLE_ID)?.ifBlank { null }
    private val argLamp: LampType? = savedStateHandle.get<String>(Routes.ARG_LAMP)
        ?.ifBlank { null }?.let { runCatching { LampType.valueOf(it) }.getOrNull() }

    private val _lamp = MutableStateFlow(argLamp ?: LampType.DAYLIGHT)
    val lamp: StateFlow<LampType> = _lamp.asStateFlow()

    // null → стол ещё не создан в БД (ни одного кадра); заводится лениво при первом снимке,
    // чтобы не плодить пустые столы при заходе/выходе из экрана.
    private val _tableId = MutableStateFlow(argTableId)
    private val _tableNumber = MutableStateFlow(1)
    val tableNumber: StateFlow<Int> = _tableNumber.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    private val _activeProfile = MutableStateFlow(CameraProfile.defaultFor(_lamp.value))
    val activeProfile: StateFlow<CameraProfile> = _activeProfile.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val cameraState: StateFlow<CameraControllerState> = controller.state

    val table: StateFlow<TableWithLayers?> = _tableId
        .flatMapLatest { id -> if (id == null) flowOf(null) else repository.observeTable(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // R16: подключение, его повтор, поверхность превью, восстановление по USB.
    private val session = CameraScreenSession(
        controller, viewModelScope, usbEvents,
        lamp = { _lamp.value },
        onMessage = { _message.value = it },
        afterSession = { _activeProfile.value = controller.activeProfile },
    )

    init {
        viewModelScope.launch {
            _tableNumber.value = if (argTableId != null) {
                repository.getTable(argTableId)?.number ?: 1
            } else {
                // Новый стол: показываем предполагаемый номер, запись создаём при первом снимке.
                repository.nextTableNumber(drillingId)
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

    fun switchLamp(lampType: LampType) {
        // R10/FIXES §7: во время снимка освещение не меняется (UI тоже заблокирован).
        if (_capturing.value || _lamp.value == lampType) return
        _lamp.value = lampType
        viewModelScope.launch {
            runCatching { controller.switchLamp(lampType) }
                .onSuccess { _activeProfile.value = controller.activeProfile }
                .onFailure { _message.value = it.message }
        }
    }

    /** «Новый стол» (§5.4): инкремент + авто-сброс на Дневной. Пустой стол не создаём. */
    fun newTable() {
        if (_capturing.value) return // R10: новый стол во время снимка сменил бы освещение и стол
        viewModelScope.launch {
            if (_tableId.value == null) {
                _message.value = "Стол №${_tableNumber.value} ещё пуст — сначала сделайте снимок."
                return@launch
            }
            _tableId.value = null
            _tableNumber.value = repository.nextTableNumber(drillingId)
            _lamp.value = LampType.DAYLIGHT
            runCatching { controller.switchLamp(LampType.DAYLIGHT) }
                .onSuccess { _activeProfile.value = controller.activeProfile }
        }
    }

    fun capture() {
        if (_capturing.value) return
        _capturing.value = true
        viewModelScope.launch {
            // Стол, в который идёт съёмка, фиксируется до снимка (как и раньше); null — стол
            // создаётся лениво при фиксации кадра (R08: неудачный снимок не оставляет пустой стол).
            val tableId = _tableId.value
            // R10: освещение фиксируется в момент нажатия; контроллер снимает ровно с профилем этой
            // лампы (применит его сам, если переключение ещё не дошло до камеры).
            val lamp = _lamp.value
            val (layerId, file) = repository.newLayerFile(drillingId)
            try {
                // R05: пересъёмка при идущей отправке — отказ ДО снимка (основная проверка — в
                // транзакции репозитория, см. saveCapture).
                repository.checkCanSaveCapture(tableId, lamp)
                val profile = controller.capture(file, lamp)
                check(profile.lampType == lamp) { "Профиль снимка не соответствует освещению" }
                _activeProfile.value = profile
                val table = repository.saveCapture(drillingId, tableId, layerId, lamp, file, profile)
                if (_tableId.value == tableId) {
                    _tableId.value = table.id
                    _tableNumber.value = table.number
                }
            } catch (e: CancellationException) {
                // Экран закрыт во время съёмки: файл удаляется, только если БД на него не ссылается.
                repository.discardUncommittedCapture(file)
                throw e
            } catch (t: Throwable) {
                repository.discardUncommittedCapture(file)
                _message.value = t.message ?: "Ошибка съёмки"
            } finally {
                _capturing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        controller.release()
    }
}
