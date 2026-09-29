package com.siams.kern_prototype.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siams.kern_prototype.R
import com.siams.kern_prototype.data.db.relation.DrillingWithTables
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.ui.components.StatusBadge
import com.siams.kern_prototype.ui.theme.Body2Bold
import com.siams.kern_prototype.ui.theme.Body3
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Red30
import com.siams.kern_prototype.viewmodel.DrillingListViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrillingListScreen(
    onOpenDrilling: (String) -> Unit,
    onNewDrilling: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenQueue: () -> Unit,
    vm: DrillingListViewModel = hiltViewModel(),
) {
    val drillings by vm.drillings.collectAsStateWithLifecycle()
    val usedBytes by vm.usedBytes.collectAsStateWithLifecycle()
    // Отказ/итог операции (удаление во время отправки, пропуски при очистке) — сообщением.
    val message by vm.message.collectAsStateWithLifecycle()
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(toastContext, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }
    var pendingDelete by remember { mutableStateOf<DrillingWithTables?>(null) }
    var pendingConfirm by remember { mutableStateOf<DrillingWithTables?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.drilling_list_title))
                        Text(
                            stringResource(
                                R.string.storage_used,
                                com.siams.kern_prototype.ui.util.formatBytes(usedBytes),
                            ),
                            style = Body3,
                            color = Gray40,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.Outlined.CloudUpload, contentDescription = stringResource(R.string.drilling_list_queue_cd))
                    }
                    IconButton(onClick = onOpenProfiles) {
                        Icon(Icons.Outlined.Tune, contentDescription = stringResource(R.string.drilling_list_profiles_cd))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.drilling_list_settings_cd))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewDrilling,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.drilling_new)) },
            )
        },
    ) { padding ->
        if (drillings.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.drilling_list_empty), style = Body3, color = Gray40)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(KernTokens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
            ) {
                items(drillings, key = { it.drilling.id }) { item ->
                    DrillingCard(
                        item = item,
                        onOpen = { onOpenDrilling(item.drilling.id) },
                        onSend = { vm.send(item.drilling.id) },
                        onDelete = { pendingDelete = item },
                        onConfirmServer = { pendingConfirm = item },
                    )
                }
            }
        }
    }

    // R03: подтверждение исходного сервера для записи до v5 (назначение неизвестно).
    val server by vm.currentServer.collectAsStateWithLifecycle()
    pendingConfirm?.let { target ->
        val dest = server.destination()
        AlertDialog(
            onDismissRequest = { pendingConfirm = null },
            title = { Text("Подтвердить сервер отправки") },
            text = {
                Text(
                    "«${target.drilling.title}» отправлялось до обновления приложения, сервер той отправки " +
                        "не записан. Подтвердите, только если оно отправлялось на ТЕКУЩИЙ сервер " +
                        "${dest.host}:${dest.port} под учётной записью «${dest.login}». Если не уверены — " +
                        "сверьте с S900: ошибочное подтверждение может изменить чужой проект.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirmServer(target.drilling.id)
                    pendingConfirm = null
                }) { Text("Подтвердить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirm = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.drilling_list_delete_title)) },
            text = { Text(stringResource(R.string.drilling_list_delete_message, target.drilling.title)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(target.drilling.id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.common_delete), color = Red30) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun DrillingCard(
    item: DrillingWithTables,
    onOpen: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
    onConfirmServer: () -> Unit,
) {
    Card(
        onClick = onOpen,
        shape = KernTokens.radiusCard,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(KernTokens.spaceLg)) {
            Text(item.drilling.title, style = Body2Bold)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = KernTokens.spaceSm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.drilling_tables_count, item.tables.size), style = Body3, color = Gray40)
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
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
                        stringResource(R.string.drilling_table_progress, uploaded, total),
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
            // R03: запись до v5 с неизвестным сервером отправки — нужно явное подтверждение.
            val d = item.drilling
            if (d.remoteId != null && d.remoteDestination == null &&
                d.syncStatus != SyncStatus.SENT && d.syncStatus != SyncStatus.SENDING
            ) {
                TextButton(onClick = onConfirmServer) { Text("Подтвердить сервер отправки") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = KernTokens.spaceSm),
                horizontalArrangement = Arrangement.End,
            ) {
                IconButton(onClick = onSend) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = stringResource(R.string.common_send))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.common_delete), tint = Red30)
                }
            }
        }
    }
}
