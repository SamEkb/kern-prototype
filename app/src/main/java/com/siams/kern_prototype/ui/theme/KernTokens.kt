package com.siams.kern_prototype.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Дизайн-токены (§14.4). Не хардкодить размеры/цвета в экранах — брать отсюда (§13).
 */
object KernTokens {
    // Спейсинг XS/SM/MD/LG/XL (§14.4)
    val spaceXs: Dp = 4.dp
    val spaceSm: Dp = 8.dp
    val spaceMd: Dp = 12.dp
    val spaceLg: Dp = 16.dp
    val spaceXl: Dp = 24.dp

    // Радиусы (§14.4)
    val radiusChip = RoundedCornerShape(2.dp)
    val radiusButton = RoundedCornerShape(4.dp)
    val radiusCard = RoundedCornerShape(8.dp)
    val radiusPill = RoundedCornerShape(percent = 50)

    // Минимальная тач-цель (§14.1)
    val minTouch: Dp = 48.dp

    /** Цвета статуса отправки долбления (§14.5, бейдж). */
    data class StatusColors(val content: Color, val container: Color)

    val statusNotSent = StatusColors(Gray40, Gray10)
    val statusSending = StatusColors(Blue, Blue10)
    val statusSent = StatusColors(Green30, Green10)
    val statusError = StatusColors(Red30, Red10)
}
