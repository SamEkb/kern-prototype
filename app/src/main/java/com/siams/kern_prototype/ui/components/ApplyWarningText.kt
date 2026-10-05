package com.siams.kern_prototype.ui.components

import com.siams.kern_prototype.camera.ApplyWarning

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
