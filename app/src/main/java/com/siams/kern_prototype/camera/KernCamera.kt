package com.siams.kern_prototype.camera

import android.util.Size
import android.view.Surface
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Состояние камеры для HUD (§5.4). Общее для всех реализаций [KernCamera]. */
sealed interface CameraControllerState {
    data object Idle : CameraControllerState
    data object Opening : CameraControllerState
    data class Ready(val previewSize: Size, val manualSupported: Boolean) : CameraControllerState
    /** [canRetry] — повторный снимок попробует переподключить камеру (кнопка спуска доступна). */
    data class Error(val message: String, val canRetry: Boolean = false) : CameraControllerState
}

/**
 * Абстракция «камеры керна»: единая точка управления съёмкой и применения
 * профилей (§6, §13), одинаковая для встроенной камеры телефона (Camera2,
 * [CameraController]) и внешней USB-камеры Touptek ([ToupcamController]).
 *
 * Порядок использования из UI/VM неизменен для обеих реализаций:
 *  1) [open] — подключить/открыть камеру и загрузить профиль под [LampType]
 *     (Ready содержит previewSize);
 *  2) UI отдаёт [Surface] превью нужного размера и зовёт [startSession];
 *  3) [switchLamp]/[applyProfile] — мгновенное применение параметров без
 *     перезапуска потока;
 *  4) [capture] — снять кадр максимального качества в файл; [release] — освободить.
 *
 * §13: никаких «сырых» вызовов камеры из экранов — только через эту абстракцию.
 */
interface KernCamera {
    /** Поток состояния для HUD. */
    val state: StateFlow<CameraControllerState>

    /** Текущий применённый профиль (для аудита идентичности и HUD). */
    val activeProfile: CameraProfile

    /**
     * R14: границы параметров текущей камеры — общие для применения профиля и редактора.
     * По умолчанию — согласованный запасной диапазон.
     */
    val limits: StateFlow<CameraLimits> get() = FALLBACK_LIMITS

    /**
     * Отказы SDK при применении профиля к ТЕКУЩЕМУ подключению (null — отказов нет или профиль
     * ещё не применён). Предупреждение для оператора; съёмку не блокирует.
     */
    val applyWarning: StateFlow<ApplyWarning?> get() = NO_APPLY_WARNING

    /**
     * Способ открытия WB текущего подключения Touptek ([WbOpenMethod]); null — камера не открыта
     * или выбор режима WB при открытии не применяется (Camera2). Не доказывает работу баланса белого.
     */
    val wbOpenMethod: StateFlow<WbOpenMethod?> get() = NO_WB_OPEN_METHOD

    /** FIXES §3: возможности камеры для интерфейса. По умолчанию — без фокуса. */
    val capabilities: CameraCapabilities get() = CameraCapabilities()

    /** Открыть/подключить камеру, выбрать размеры, загрузить профиль под [lampType]. */
    suspend fun open(lampType: LampType)

    /** Создать сессию превью на [surface] и запустить поток кадров с текущим профилем. */
    suspend fun startSession(surface: Surface)

    /** Мгновенно применить профиль без перезапуска потока (§6.4). */
    suspend fun applyProfile(profile: CameraProfile)

    /** Переключить тип освещения: прочитать сохранённый профиль и применить (§6.4). */
    suspend fun switchLamp(lampType: LampType)

    /**
     * Снять кадр максимального качества в [target] (§6.5). Возвращает применённый
     * профиль (для аудита идентичности).
     */
    suspend fun capture(target: File, lampType: LampType): CaptureOutcome

    /**
     * §2.1: автоэкспозиция «в одно нажатие». Включает авто-AE с приоритетом выдержки
     * (усиление ограничено во избежание зернистости), даёт сойтись, замораживает
     * авто и возвращает [current] с подставленными выдержкой и усилением.
     * По умолчанию — не поддерживается (возвращает [current] без изменений).
     */
    suspend fun autoExposure(current: CameraProfile): CameraProfile = current

    /**
     * §2.2: автобаланс белого «в одно нажатие». Возвращает [current] с подставленными
     * поканальными усилениями (R/G/B). По умолчанию — не поддерживается.
     */
    suspend fun autoWhiteBalance(current: CameraProfile): CameraProfile = current

    /**
     * §2.3: снять эталонный кадр FFC для [lampType] (по белому равномерному фону),
     * применить лёгкое размытие и сохранить шаблон неравномерности. Возвращает путь
     * к файлу шаблона (его нужно записать в профиль), либо null — если не поддерживается.
     * Коррекция снимков делением на шаблон выполняется внутри [capture] по
     * [CameraProfile.ffcTemplatePath].
     *
     * R22: эталон принимается только после проверки качества ([FfcQuality]); непригодный —
     * [FfcQuality.RejectedException], файл не создаётся.
     */
    suspend fun captureFfcReference(lampType: LampType): FfcReference? = null

    /**
     * R16: больше не рисовать на [surface]. Блокирует до окончания текущей отрисовки на неё: после
     * возврата [surface] (и её текстуру) можно освобождать — независимо от состояния подключения.
     * Отсоединяет только этот экземпляр: если подключена уже другая поверхность, она не затрагивается.
     * Подключение к камере сохраняется.
     */
    fun detachSurface(surface: Surface) {}

    /**
     * R16: восстановить подключение после ошибки/отключения камеры (в т.ч. если первое открытие не
     * удалось). По умолчанию — не поддерживается.
     */
    suspend fun recover() {}

    /** Освободить ресурсы камеры. */
    fun release()
}

private val FALLBACK_LIMITS: StateFlow<CameraLimits> =
    kotlinx.coroutines.flow.MutableStateFlow(CameraLimits.FALLBACK)

/** R22: принятый эталон FFC — путь к шаблону и отчёт проверки качества. */
data class FfcReference(val path: String, val quality: FfcQuality.Report)

/** Отклонённые SDK сеттеры (имя → HRESULT) при применении профиля лампы [lampType]. */
data class ApplyWarning(val lampType: LampType, val rejected: Map<String, String>)

private val NO_APPLY_WARNING: StateFlow<ApplyWarning?> = kotlinx.coroutines.flow.MutableStateFlow(null)

private val NO_WB_OPEN_METHOD: StateFlow<WbOpenMethod?> = kotlinx.coroutines.flow.MutableStateFlow(null)
