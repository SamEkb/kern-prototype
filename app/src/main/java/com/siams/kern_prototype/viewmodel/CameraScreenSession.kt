package com.siams.kern_prototype.viewmodel

import android.graphics.SurfaceTexture
import android.util.Log
import android.view.Surface
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.KernCamera
import com.siams.kern_prototype.camera.UsbDeviceEvents
import com.siams.kern_prototype.domain.model.LampType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * R16: общее для экранов камеры (съёмка, редактор профилей) — подключение и его повтор,
 * поверхность превью и восстановление при повторном подключении USB.
 *
 *  - Подключение: первое — [KernCamera.open], повторные — [KernCamera.recover]. Неудача не
 *    блокирует повтор: «Переподключить» ([reconnect]) или подключение USB-устройства, если камера
 *    в ошибке ([UsbDeviceEvents]).
 *  - Поверхность: ВСЕ обращения к камере по поводу поверхности (подключение, отсоединение) и её
 *    освобождение выполняются по порядку в [surfaceExecutor] — процессном потоке, не зависящем от
 *    [scope]: главный поток не ждёт отрисовку кадра, очистка переживает закрытие экрана.
 *    Главный поток лишь меняет «желаемую» поверхность ([desired]); отложенное подключение
 *    выполняется, только если его поверхность всё ещё желаемая на момент исполнения, а
 *    отсоединение привязано к экземпляру — поэтому итог совпадает с последним решением экрана при
 *    любом порядке событий. Текстура освобождается только после подтверждённого
 *    [KernCamera.detachSurface]; если отсоединение не удалось — не освобождается.
 *  - Фон ([onStop]/[onStart]): поверхность явно отсоединяется и подключается снова (при
 *    сворачивании текстура у TextureView не уничтожается). Подключение к камере сохраняется.
 *
 * Методы вызываются в главном потоке (как и [scope]).
 */
internal class CameraScreenSession(
    private val controller: KernCamera,
    private val scope: CoroutineScope,
    usbEvents: UsbDeviceEvents,
    private val lamp: () -> LampType,
    private val onMessage: (String?) -> Unit,
    /** После подключения поверхности (обновить HUD/применить рабочий профиль). */
    private val afterSession: suspend () -> Unit,
    /** Освобождение текстуры превью (выделено для проверки порядка в тестах). */
    private val releaseTexture: (SurfaceTexture) -> Unit = { it.release() },
    /** Поток переходов поверхности; по умолчанию — общий процессный. */
    private val surfaceExecutor: Executor = SURFACE_EXECUTOR,
    /** Создание Surface для текстуры (выделено для тестов). */
    private val newSurface: (SurfaceTexture) -> Surface = { Surface(it) },
) {
    private var connecting = false
    private var connectedOnce = false
    private var stopped = false

    /** Текстура, которую сейчас показывает экран (главный поток). */
    private var texture: SurfaceTexture? = null

    /** Surface каждой живой текстуры — по экземпляру (главный поток). */
    private val surfaces = IdentityHashMap<SurfaceTexture, Surface>()

    /** Поверхность, на которую экран хочет превью (null — никакую). Читается очередью. */
    @Volatile
    private var desired: Surface? = null

    init {
        scope.launch {
            usbEvents.attached.collect {
                if (controller.state.value is CameraControllerState.Error) reconnect()
            }
        }
    }

    /** Вход на экран: подключиться (однократно; повтор — [reconnect]). */
    fun start() {
        if (!connectedOnce) connect()
    }

    /** «Переподключить» / USB-устройство подключено снова. */
    fun reconnect() = connect()

    private fun connect() {
        if (connecting) return
        connecting = true
        scope.launch {
            try {
                runCatching { if (connectedOnce) controller.recover() else controller.open(lamp()) }
                    .onSuccess {
                        connectedOnce = true
                        attachIfReady()
                    }
                    .onFailure { onMessage(it.message) }
            } finally {
                connecting = false
            }
        }
    }

    fun onSurfaceAvailable(st: SurfaceTexture) {
        texture = st
        attachIfReady()
    }

    /**
     * Текстура [st] уничтожается. Возвращает false: текстуру освобождает сессия — после того, как
     * камера подтвердила, что больше не рисует на ЕЁ поверхность.
     */
    fun onSurfaceDestroyed(st: SurfaceTexture): Boolean {
        if (texture === st) texture = null
        val s = surfaces.remove(st)
        if (s != null && desired === s) desired = null
        surfaceExecutor.execute {
            val detached = s == null || runCatching { controller.detachSurface(s) }
                .onFailure { Log.e(TAG, "Поверхность не отсоединена — текстура не освобождается", it) }
                .isSuccess
            if (detached) {
                runCatching { s?.release() }
                runCatching { releaseTexture(st) }
            }
        }
        return false
    }

    /** Экран остановлен (фон): камера перестаёт рисовать на поверхность. */
    fun onStop() {
        stopped = true
        val s = desired ?: return
        desired = null
        surfaceExecutor.execute { runCatching { controller.detachSurface(s) } }
    }

    /** Экран снова виден: поверхность подключается обратно. */
    fun onStart() {
        if (!stopped) return
        stopped = false
        attachIfReady()
    }

    /**
     * Сделать поверхность текущей текстуры желаемой и поставить её подключение в очередь.
     * В очереди подключение выполнится, только если к тому времени она всё ещё желаемая.
     */
    private fun attachIfReady() {
        val st = texture ?: return
        if (stopped) return
        val state = controller.state.value as? CameraControllerState.Ready ?: return
        val s = surfaces[st] ?: run {
            st.setDefaultBufferSize(state.previewSize.width, state.previewSize.height)
            newSurface(st).also { surfaces[st] = it }
        }
        desired = s
        surfaceExecutor.execute {
            if (desired !== s) return@execute // решение экрана уже изменилось
            runCatching { runBlocking { controller.startSession(s) } }
                .onSuccess { if (scope.isActive) scope.launch { afterSession() } }
                .onFailure { e -> if (scope.isActive) scope.launch { onMessage(e.message) } }
        }
    }

    companion object {
        private const val TAG = "CameraScreenSession"

        /** Процессный поток переходов поверхности превью (не привязан к жизни экрана). */
        private val SURFACE_EXECUTOR: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "camera-surface").apply { isDaemon = true }
        }
    }
}
