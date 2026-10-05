package com.siams.kern_prototype.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siams.kern_prototype.R
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.ui.components.StatusBadge
import com.siams.kern_prototype.ui.theme.Body2Bold
import com.siams.kern_prototype.ui.theme.Body3
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.components.readableWidth
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Red30
import com.siams.kern_prototype.viewmodel.DrillingListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendQueueScreen(
    onBack: () -> Unit,
    vm: DrillingListViewModel = hiltViewModel(),
) {
    val drillings by vm.drillings.collectAsStateWithLifecycle()
    val usedBytes by vm.usedBytes.collectAsStateWithLifecycle()
    // Итог «Очистить отправленные» (пропущенные из-за смены статуса) — сообщением.
    val message by vm.message.collectAsStateWithLifecycle()
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(toastContext, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }
    var confirmClear by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    val pending = drillings.count {
        it.drilling.syncStatus == SyncStatus.NOT_SENT || it.drilling.syncStatus == SyncStatus.ERROR
    }
    val sent = drillings.count { it.drilling.syncStatus == SyncStatus.SENT }
    // Выбирать можно всё, кроме уже идущей отправки.
    val selectableIds = drillings
        .filter { it.drilling.syncStatus != SyncStatus.SENDING }
        .map { it.drilling.id }
        .toSet()
    // Убрать из выбора исчезнувшие/ушедшие в SENDING элементы.
    selected = selected.intersect(selectableIds)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.send_queue_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).readableWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(KernTokens.spaceLg),
                horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
            ) {
                val hasSelection = selected.isNotEmpty()
                Button(
                    onClick = {
                        if (hasSelection) {
                            vm.sendAll(selected.toList()); selected = emptySet()
                        } else {
                            vm.sendPending()
                        }
                    },
                    enabled = hasSelection || pending > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (hasSelection) stringResource(R.string.send_queue_send_selected, selected.size)
                        else stringResource(R.string.send_queue_send_pending, pending),
                    )
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = sent > 0,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.send_queue_clear, sent)) }
            }

            if (selectableIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = KernTokens.spaceLg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = {
                        selected = if (selected.size == selectableIds.size) emptySet() else selectableIds
                    }) {
                        Text(
                            if (selected.size == selectableIds.size) stringResource(R.string.send_queue_deselect_all)
                            else stringResource(R.string.send_queue_select_all),
                        )
                    }
                }
            }

            Text(
                stringResource(
                    R.string.storage_used,
                    com.siams.kern_prototype.ui.util.formatBytes(usedBytes),
                ),
                style = Body3,
                color = Gray40,
                modifier = Modifier.padding(horizontal = KernTokens.spaceLg, vertical = KernTokens.spaceXs),
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = KernTokens.spaceLg,
                ),
                verticalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
            ) {
                items(drillings, key = { it.drilling.id }) { item ->
                    val id = item.drilling.id
                    val selectable = item.drilling.syncStatus != SyncStatus.SENDING
                    Card(shape = KernTokens.radiusCard, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(KernTokens.spaceLg)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + id else selected - id
                                    },
                                    enabled = selectable,
                                )
                                Text(item.drilling.title, style = Body2Bold, modifier = Modifier.weight(1f))
                                StatusBadge(item.drilling.syncStatus)
                            }
                            if (item.drilling.syncStatus == SyncStatus.SENDING) {
                                val total = item.drilling.totalTables
                                val uploaded = item.drilling.uploadedTables
                                if (total > 0) {
                                    LinearProgressIndicator(
                                        progress = { uploaded.toFloat() / total },
                                        modifier = Modifier.fillMaxWidth().padding(top = KernTokens.spaceSm),
                                    )
                                    Text(
                                        stringResource(R.string.send_queue_progress, uploaded, total),
                                        style = Body3, color = Gray40,
                                        modifier = Modifier.padding(top = KernTokens.spaceXs),
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().padding(top = KernTokens.spaceSm),
                                    )
                                }
                            }
                            item.drilling.lastError?.let {
                                Text(it, style = Body3, color = Red30, modifier = Modifier.padding(top = KernTokens.spaceXs))
                            }
                            if (item.drilling.syncStatus == SyncStatus.ERROR ||
                                item.drilling.syncStatus == SyncStatus.NOT_SENT
                            ) {
                                OutlinedButton(
                                    onClick = { vm.send(item.drilling.id) },
                                    modifier = Modifier.padding(top = KernTokens.spaceSm),
                                ) { Text(stringResource(R.string.common_send)) }
                            }
                        }
                    }
                }
                if (drillings.isEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.send_queue_empty),
                            style = Body3, color = Gray40,
                            modifier = Modifier.padding(KernTokens.spaceLg),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.send_queue_clear_title)) },
            // R04/R06: «Отправлено» не доказывает полноту архива на сервере.
            text = { Text(stringResource(R.string.send_queue_clear_message, sent) + "\n\n" + stringResource(R.string.send_queue_clear_note)) },
            confirmButton = {
                TextButton(onClick = { vm.clearSent(); confirmClear = false }) {
                    Text(stringResource(R.string.common_clear), color = Red30)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
