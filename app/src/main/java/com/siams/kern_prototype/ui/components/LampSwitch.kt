package com.siams.kern_prototype.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.siams.kern_prototype.R
import com.siams.kern_prototype.ui.theme.Green30
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
 *
 * FIXES §6: [captured] — освещения, кадр которых уже зафиксирован в столе. У такого сегмента —
 * галочка, рамка [KernTokens.borderStrong] цвета [Green30] и подпись «… · снят» независимо от выбора
 * (заливку выбранного сегмента не трогаем: «выбран» и «снят» различимы). В редакторе профилей пусто.
 */
@Composable
fun LampSwitch(
    selected: LampType,
    onSelect: (LampType) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    captured: Set<LampType> = emptySet(),
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
            captured = LampType.DAYLIGHT in captured,
            active = selected == LampType.DAYLIGHT,
            activeBg = Blue,
            activeContent = Color.White,
            modifier = Modifier.weight(1f).fillMaxHeight().clickable(enabled = enabled) { onSelect(LampType.DAYLIGHT) },
        )
        Segment(
            label = LampType.UF.ruLabel,
            captured = LampType.UF in captured,
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
    captured: Boolean,
    active: Boolean,
    activeBg: Color,
    activeContent: Color,
    modifier: Modifier = Modifier,
) {
    val description = if (captured) stringResource(R.string.lamp_captured_cd, label) else label
    Row(
        modifier = modifier
            .background(if (active) activeBg else Color.Transparent, KernTokens.radiusPill)
            .then(if (captured) Modifier.border(KernTokens.borderStrong, Green30, KernTokens.radiusPill) else Modifier)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = KernTokens.spaceMd),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (captured) {
            Icon(
                Icons.Outlined.CheckCircle, contentDescription = null,
                tint = if (active) activeContent else Green30,
                modifier = Modifier.size(KernTokens.spaceXl).padding(end = KernTokens.spaceXs),
            )
        }
        Text(
            text = if (captured) stringResource(R.string.lamp_captured, label) else label,
            style = Body2Bold,
            color = if (active) activeContent else Color.White,
            textAlign = TextAlign.Center,
        )
    }
}
