package com.siams.kern_prototype.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.siams.kern_prototype.R
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.data.hasServerCopy
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.ui.theme.Body3Bold
import com.siams.kern_prototype.ui.theme.Body4Bold
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.theme.Ink
import com.siams.kern_prototype.ui.components.TablesGrid
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Red30
import com.siams.kern_prototype.ui.theme.UvYellow
import com.siams.kern_prototype.viewmodel.TablesViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TablesScreen(
    onBack: () -> Unit,
    onOpenTable: (String) -> Unit,
    onAddCapture: () -> Unit,
    onEditPassport: () -> Unit,
    vm: TablesViewModel = hiltViewModel(),
) {
    val drilling by vm.drilling.collectAsStateWithLifecycle()
    val tables by vm.tables.collectAsStateWithLifecycle()
    // Отказ операции (например, удаление во время отправки) — коротким сообщением.
    val message by vm.message.collectAsStateWithLifecycle()
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(toastContext, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }

    // Групповое удаление: долгое нажатие на плитку включает выбор; пустой выбор — обычный режим.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Убрать из выбора исчезнувшие столы (удалены здесь или в другом месте).
    selected = selected.intersect(tables.map { it.table.id }.toSet())
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(stringResource(R.string.tables_selected, selected.size)) },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.tables_selection_cancel_cd))
                        }
                    },
                    actions = {
                        val all = selected.size == tables.size
                        TextButton(onClick = { selected = if (all) emptySet() else tables.map { it.table.id }.toSet() }) {
                            Text(stringResource(if (all) R.string.send_queue_deselect_all else R.string.send_queue_select_all))
                        }
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.tables_delete_selected_cd))
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = { Text(drilling?.title ?: stringResource(R.string.tables_title_fallback)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onEditPassport) {
                            Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.tables_passport_cd))
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    onClick = onAddCapture,
                    icon = { Icon(Icons.Outlined.AddAPhoto, contentDescription = null) },
                    text = { Text(stringResource(R.string.tables_capture)) },
                )
            }
        },
    ) { padding ->
        if (tables.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.tables_empty), color = Gray40, style = Body3Bold)
            }
        } else {
            // FIXES §8: на телефоне — 2 колонки, на планшете/в альбомной ориентации — больше.
            TablesGrid(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(tables, key = { it.table.id }) { item ->
                    val id = item.table.id
                    val toggle = { selected = if (id in selected) selected - id else selected + id }
                    TableCard(
                        item = item,
                        selected = id in selected,
                        onClick = { if (selecting) toggle() else onOpenTable(id) },
                        onLongClick = toggle,
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        val chosen = tables.filter { it.table.id in selected }
        val onServer = chosen.any { t -> t.layers.any { it.hasServerCopy() } }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.tables_delete_title)) },
            text = {
                // Сначала количество и предупреждение S900, затем номера — короткий список, чтобы
                // перечисление не вытесняло предупреждение; всё вместе прокручивается.
                val numbers = chosen.take(DELETE_LIST_MAX).joinToString(", ") { "№" + it.table.number } +
                    if (chosen.size > DELETE_LIST_MAX) " " + stringResource(R.string.tables_delete_more, chosen.size - DELETE_LIST_MAX) else ""
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.tables_delete_message, chosen.size))
                    if (onServer) Text(stringResource(R.string.tables_delete_sent_note), Modifier.padding(top = KernTokens.spaceSm))
                    Text(stringResource(R.string.tables_delete_numbers, numbers), Modifier.padding(top = KernTokens.spaceSm))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    // Выбор снимается только после удаления: при отказе (R05, ошибка БД) он остаётся.
                    vm.deleteTables(chosen.map { it.table.id }.toSet()) { selected = emptySet() }
                }) {
                    Text(stringResource(R.string.common_delete), color = Red30)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Сколько номеров столов перечислять в подтверждении удаления. */
private const val DELETE_LIST_MAX = 12

@Composable
private fun TableCard(item: TableWithLayers, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val daylight = item.layers.firstOrNull { it.lampType == LampType.DAYLIGHT }
    val hasUf = item.layers.any { it.lampType == LampType.UF }

    Card(
        shape = KernTokens.radiusCard,
        border = if (selected) BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            if (daylight != null) {
                AsyncImage(
                    model = File(daylight.thumbPath ?: daylight.filePath),
                    contentDescription = stringResource(R.string.table_number, item.table.number),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize().background(Ink.copy(alpha = 0.08f)))
            }
            // Номер стола на белой плашке (§14.5).
            Text(
                stringResource(R.string.table_number, item.table.number),
                style = Body4Bold,
                color = Ink,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(KernTokens.spaceSm)
                    .background(Color.White, KernTokens.radiusChip)
                    .padding(horizontal = KernTokens.spaceSm, vertical = KernTokens.spaceXs),
            )
            if (hasUf) {
                Text(
                    stringResource(R.string.common_uf),
                    style = Body4Bold,
                    color = Ink,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(KernTokens.spaceSm)
                        .background(UvYellow, KernTokens.radiusChip)
                        .padding(horizontal = KernTokens.spaceSm, vertical = KernTokens.spaceXs),
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.tables_selected_cd),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(KernTokens.spaceSm)
                        .background(Color.White, androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
    }
}
