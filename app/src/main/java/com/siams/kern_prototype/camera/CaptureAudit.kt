package com.siams.kern_prototype.camera

import kotlinx.serialization.Serializable

/**
 * R14: аудит параметров снимка, собранный внутри той же сериализованной операции съёмки
 * (переключение профиля или переподключение не может подмешать чужие значения):
 *  - [requested] — исходный запрос профиля (как задан пользователем);
 *  - [applied] — значения, отправленные в SDK после ограничения границами [limits], и результат
 *    каждого сеттера (HRESULT);
 *  - [readback] — состояние SDK, прочитанное непосредственно перед Snap; null — прочитать не
 *    удалось («неизвестно»), отдельное поле null — SDK не сообщил это значение. Это состояние
 *    SDK около снимка, а не доказанные параметры экспонирования именно этого кадра.
 */
@Serializable
data class CaptureAudit(
    val requested: Requested,
    val applied: ToupcamProfileApplier.ApplyReport?,
    val limits: CameraLimits,
    val readback: CameraReadback?,
    /** R21: замер памяти операции снимка (null — не измерялось). */
    val memory: MemoryTrace? = null,
    /** R21: замер памяти сохранения слоя (проверка JPEG, SHA-256, миниатюра). */
    val saveMemory: MemoryTrace? = null,
    /** Способ открытия WB подключения, на котором сделан снимок ([WbOpenMethod]); null — не известен. */
    val wbOpenMethod: WbOpenMethod? = null,
    val note: String = "readback — состояние SDK около снимка, не доказанные параметры кадра",
) {
    /** Запрос в единицах профиля. */
    @Serializable
    data class Requested(
        val exposureTimeNs: Long,
        val iso: Int,
        val gamma: Int,
        val wbRedGain: Float,
        val wbGreenGain: Float,
        val wbBlueGain: Float,
    )
}

/** Результат снимка: подтверждённый профиль и аудит параметров (null — камера аудит не ведёт). */
data class CaptureOutcome(
    val profile: com.siams.kern_prototype.domain.model.CameraProfile,
    val audit: CaptureAudit?,
)
