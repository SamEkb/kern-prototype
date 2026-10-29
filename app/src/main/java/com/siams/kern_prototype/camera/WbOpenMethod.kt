package com.siams.kern_prototype.camera

/**
 * Способ открытия WB: с каким параметром камера Touptek открыта в `Toupcam_Open` (jni/wb_open.h).
 * Показывает только запрос режима RGB Gain, а не работу баланса белого: её подтверждают
 * результаты чтения/записи WhiteBalanceGain и AWB. Сбрасывается при каждом открытии.
 */
enum class WbOpenMethod(val code: Int) {
    /** Открыта с `;wb=rgb` — режим RGB Gain запрошен. */
    RGB_SUFFIX(1),

    /** С `;wb=rgb` открыть не удалось; открыта без суффикса — режим WB не выбран (по умолчанию SDK). */
    FALLBACK_NO_SUFFIX(2),

    /** JNI не сообщил известный способ (камера открыта, код не распознан). */
    UNKNOWN(-1);

    companion object {
        fun fromNative(code: Int): WbOpenMethod = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}
