package com.siams.kern_prototype.ui.components

import com.siams.kern_prototype.camera.ApplyWarning
import com.siams.kern_prototype.camera.WbOpenMethod

/** Понятные названия сеттеров SDK. */
private val SETTER_NAMES = mapOf(
    "AutoExpoEnable" to "отключение автоэкспозиции",
    "ExpoTime" to "выдержка",
    "ExpoAGain" to "усиление",
    "WhiteBalanceGain" to "баланс белого",
    "Gamma" to "гамма",
)

/**
 * Текст предупреждения об отказах SDK: какие параметры профиля лампы камера не приняла (с кодами).
 * Не утверждает, каким получится кадр, — только что параметры профиля могли не примениться.
 */
fun applyWarningText(w: ApplyWarning): String {
    val items = w.rejected.entries.joinToString { (op, hr) -> "${SETTER_NAMES[op] ?: op} ($hr)" }
    return "Камера не приняла параметры профиля «${w.lampType.ruLabel}»: $items. " +
        "Снимки могут не соответствовать профилю; съёмка не заблокирована."
}

/**
 * Предупреждение о способе открытия WB: камера открыта без запроса режима RGB Gain (резервный путь)
 * или способ неизвестен. null — режим RGB Gain запрошен или камера не открыта; это ещё не значит,
 * что баланс белого работает.
 */
fun wbOpenWarningText(method: WbOpenMethod?): String? = when (method) {
    null, WbOpenMethod.RGB_SUFFIX -> null
    WbOpenMethod.FALLBACK_NO_SUFFIX ->
        "Камера открыта в резервном режиме: режим баланса белого RGB Gain не выбран. " +
            "Ручной баланс белого и автобаланс не подтверждены; съёмка не заблокирована."
    WbOpenMethod.UNKNOWN ->
        "Способ открытия камеры для баланса белого неизвестен. " +
            "Ручной баланс белого и автобаланс не подтверждены; съёмка не заблокирована."
}
