package com.siams.kern_prototype.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.ui.theme.Blue
import com.siams.kern_prototype.ui.theme.Body2Bold
import com.siams.kern_prototype.ui.theme.Gray50
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.UvYellow

/**
 * Сегмент-контрол Дневной ⇄ УФ (§14.5). Крупный (для перчаток), pill.
 * Активный Дневной — синий, активный УФ — тёмный с жёлтой меткой.
 */
@Composable
fun LampSwitch(
    selected: LampType,
    onSelect: (LampType) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .height(KernTokens.minTouch)
            .clip(KernTokens.radiusPill)
            .border(1.dp, Gray50, KernTokens.radiusPill),
    ) {
        Segment(
            label = LampType.DAYLIGHT.ruLabel,
            active = selected == LampType.DAYLIGHT,
            activeBg = Blue,
            activeContent = Color.White,
            modifier = Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled) { onSelect(LampType.DAYLIGHT) },
        )
        Segment(
            label = LampType.UF.ruLabel,
            active = selected == LampType.UF,
            activeBg = Gray50,
            activeContent = UvYellow,
            modifier = Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled) { onSelect(LampType.UF) },
        )
    }
}

@Composable
private fun Segment(
    label: String,
    active: Boolean,
    activeBg: Color,
    activeContent: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(if (active) activeBg else Color.Transparent, KernTokens.radiusPill)
            .padding(horizontal = KernTokens.spaceMd),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = Body2Bold,
            color = if (active) activeContent else Color.White,
            textAlign = TextAlign.Center,
        )
    }
}
