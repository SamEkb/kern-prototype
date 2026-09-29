package com.siams.kern_prototype.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Типографика §14.3. Шкала BODY1..4 = 18/16/14/12sp, у каждого есть *_BOLD.
 * Бренд-шрифт — Lato; TTF пока не подключён → фолбэк системный sans-serif (§14.3).
 * Чтобы включить Lato: положить lato_regular/lato_bold в res/font и заменить [KernFont].
 */
private val KernFont = FontFamily.SansSerif

// Шкала §14.3 — размеры задаются ТОЛЬКО этими токенами (не произвольные значения).
val Body1 = TextStyle(fontFamily = KernFont, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 24.sp)
val Body1Bold = Body1.copy(fontWeight = FontWeight.Bold)
val Body2 = TextStyle(fontFamily = KernFont, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
val Body2Bold = Body2.copy(fontWeight = FontWeight.Bold)
val Body3 = TextStyle(fontFamily = KernFont, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
val Body3Bold = Body3.copy(fontWeight = FontWeight.Bold)
val Body4 = TextStyle(fontFamily = KernFont, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
val Body4Bold = Body4.copy(fontWeight = FontWeight.Bold)

/** Маппинг шкалы на роли Material3. Заголовки экранов — Body1Bold, тело — Body2/3, каптионы — Body4. */
val KernTypography = Typography(
    titleLarge = Body1Bold,
    titleMedium = Body2Bold,
    bodyLarge = Body2,
    bodyMedium = Body3,
    labelLarge = Body3Bold,
    labelMedium = Body4,
    labelSmall = Body4,
)
