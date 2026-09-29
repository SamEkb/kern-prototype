package com.siams.kern_prototype.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.siams.kern_prototype.R
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.ui.theme.Body4Bold
import com.siams.kern_prototype.ui.theme.KernTokens

/** Бейдж статуса отправки (§14.5). Pill, BODY4. */
@Composable
fun StatusBadge(status: SyncStatus, modifier: Modifier = Modifier) {
    val (text, colors) = when (status) {
        SyncStatus.NOT_SENT -> stringResource(R.string.status_not_sent) to KernTokens.statusNotSent
        SyncStatus.SENDING -> stringResource(R.string.status_sending) to KernTokens.statusSending
        SyncStatus.SENT -> stringResource(R.string.status_sent) to KernTokens.statusSent
        SyncStatus.ERROR -> stringResource(R.string.status_error) to KernTokens.statusError
    }
    Box(
        modifier = modifier
            .background(colors.container, KernTokens.radiusPill)
            .padding(horizontal = KernTokens.spaceMd, vertical = KernTokens.spaceXs),
    ) {
        Text(text = text, style = Body4Bold, color = colors.content, textAlign = TextAlign.Center)
    }
}
