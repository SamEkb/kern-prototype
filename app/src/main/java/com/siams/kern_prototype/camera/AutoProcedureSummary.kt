package com.siams.kern_prototype.camera

import kotlinx.serialization.Serializable

/**
 * Диагностика WB/AE (5k): итог автопроцедуры камеры — для журнала и разбора на устройстве.
 *
 *  - [calls] — вызовы SDK процедуры и их HRESULT (каждый также пишется в журнал по мере вызова);
 *  - [result] — значения, записанные в профиль ([Result]); null — процедура не завершилась;
 *  - [readbackAfter] — состояние SDK, прочитанное ПОСЛЕ процедуры (null — прочитать не удалось:
 *    «неизвестно»). Это состояние SDK, а не доказанные параметры какого-либо кадра;
 *  - [events] — события SDK, ПОСТУПИВШИЕ за время процедуры (кроме кадров превью/снимка); время —
 *    от начала процедуры до поступления события (не до его обработки в очереди).
 */
@Serializable
data class AutoProcedureSummary(
    val procedure: String,
    val outcome: String,
    val durationMs: Long,
    val calls: List<ToupcamController.SdkCall>,
    val result: Result?,
    val readbackAfter: CameraReadback?,
    val events: List<SdkEventRecord>,
    /** Часть событий окна процедуры вытеснена из буфера — список [events] неполный. */
    val eventsTruncated: Boolean = false,
) {
    @Serializable
    data class Result(val exposureUs: Long, val gainPercent: Int, val wbRed: Float, val wbGreen: Float, val wbBlue: Float)

    fun describe(): String {
        val callsText = calls.joinToString { "${it.op}(${it.args})=${it.hr}" }
        val eventsText = (if (events.isEmpty()) "нет" else events.joinToString { "${it.name}@${it.atMs}мс" }) +
            if (eventsTruncated) " (неполно: часть вытеснена)" else ""
        return "$procedure: $outcome за ${durationMs} мс; вызовы: $callsText; в профиль: ${result ?: "—"}; " +
            "состояние SDK после: ${readbackAfter ?: "неизвестно"}; события: $eventsText"
    }
}

/** Событие SDK: код, имя по toupcam.h и время поступления от начала процедуры, мс. */
@Serializable
data class SdkEventRecord(val code: Int, val name: String, val atMs: Long)

/** Имена кодов событий SDK (toupcam.h). */
object SdkEvents {
    private val names = mapOf(
        0x0001 to "EXPOSURE", 0x0002 to "TEMPTINT", 0x0004 to "IMAGE", 0x0005 to "STILLIMAGE",
        0x0006 to "WBGAIN", 0x0007 to "TRIGGERFAIL", 0x0008 to "BLACK", 0x0009 to "FFC", 0x000a to "DFC",
        0x000b to "ROI", 0x000c to "LEVELRANGE", 0x000d to "AUTOEXPO_CONV", 0x000e to "AUTOEXPO_CONVFAIL",
        0x000f to "FPNC", 0x0080 to "ERROR", 0x0081 to "DISCONNECTED", 0x0082 to "NOFRAMETIMEOUT",
        0x0084 to "FOCUSPOS", 0x0085 to "NOPACKETTIMEOUT", 0x4000 to "EXPO_START", 0x4001 to "EXPO_STOP",
        0x4002 to "TRIGGER_ALLOW", 0x4003 to "HEARTBEAT", 0x4004 to "TRIGGER_IN", 0x8001 to "FACTORY",
    )

    fun name(code: Int): String = names[code] ?: "0x%04x".format(code)
}
