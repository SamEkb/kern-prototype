package com.siams.kern_prototype.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.siams.kern_prototype.R
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.ui.theme.Body3
import com.siams.kern_prototype.ui.theme.Body3Bold
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.theme.Orange30
import com.siams.kern_prototype.ui.theme.Ink
import com.siams.kern_prototype.ui.theme.KernTheme
import com.siams.kern_prototype.ui.components.AdaptiveSplit
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Red30
import com.siams.kern_prototype.ui.theme.UvYellow
import com.siams.kern_prototype.viewmodel.TableViewViewModel
import com.siams.kern_prototype.data.DepthInput
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TableViewScreen(
    onBack: () -> Unit,
    onReshoot: (drillingId: String, lamp: String) -> Unit,
    vm: TableViewViewModel = hiltViewModel(),
) = KernTheme(dark = true) {
    val table by vm.table.collectAsStateWithLifecycle()
    val t = table
    val drillingId = t?.table?.drillingId.orEmpty()

    val daylight = t?.layers?.firstOrNull { it.lampType == LampType.DAYLIGHT }
    val uf = t?.layers?.firstOrNull { it.lampType == LampType.UF }
    var overlay by remember { mutableFloatStateOf(0f) }

    val closed by vm.closed.collectAsStateWithLifecycle()
    // Отказ операции (например, удаление во время отправки) — коротким сообщением.
    val message by vm.message.collectAsStateWithLifecycle()
    val toastContext = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(toastContext, it, android.widget.Toast.LENGTH_LONG).show()
            vm.consumeMessage()
        }
    }
    LaunchedEffect(closed) { if (closed) onBack() }
    // R15: выход и «Переснять» дожидаются автосохранения глубин.
    val leave = { vm.flush(onDone = onBack) }
    BackHandler(onBack = leave)

    var showEditNumber by remember { mutableStateOf(false) }
    var showDeleteTable by remember { mutableStateOf(false) }
    var pendingDeleteLayer by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = Ink,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.table_number, t?.table?.number ?: "—")) },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { showEditNumber = true }, enabled = t != null) {
                        Icon(Icons.Outlined.Edit, contentDescription = stringResource(R.string.table_view_edit_number_cd))
                    }
                    IconButton(onClick = { showDeleteTable = true }, enabled = t != null) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.table_view_delete_table_cd), tint = Red30)
                    }
                },
            )
        },
    ) { padding ->
        // FIXES §8: в широком окне — изображение слева, слои и глубины справа; иначе одна колонка.
        AdaptiveSplit(
            sideWidth = KernTokens.sidePanelWideWidth,
            mainAspect = 4f / 3f,
            modifier = Modifier.padding(padding),
            // Наложение слоёв: Дневной снизу, УФ сверху с alpha = overlay (§5.5).
            main = { m ->
                Box(m.background(Color.Black), contentAlignment = Alignment.Center) {
                if (daylight != null) {
                    AsyncImage(
                        model = File(daylight.filePath),
                        contentDescription = stringResource(R.string.common_daylight),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (uf != null) {
                    AsyncImage(
                        model = File(uf.filePath),
                        contentDescription = stringResource(R.string.common_uf),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().alpha(if (daylight == null) 1f else overlay),
                    )
                }
                if (daylight == null && uf == null) {
                    Text(stringResource(R.string.table_view_no_images), color = Color.White, style = Body3)
                }
                }
            },
            side = {

            if (daylight != null && uf != null) {
                Text(stringResource(R.string.table_view_overlay), style = Body3, color = Color.White)
                Slider(value = overlay, onValueChange = { overlay = it })
            }

            // Управление слоями.
            LayerRow(
                label = stringResource(R.string.common_daylight),
                present = daylight != null,
                labelColor = Color.White,
                onReshoot = { vm.flush(leaving = false) { onReshoot(drillingId, LampType.DAYLIGHT.name) } },
                onDelete = daylight?.let { { pendingDeleteLayer = it.id } },
            )
            LayerRow(
                label = stringResource(R.string.common_uf),
                present = uf != null,
                labelColor = UvYellow,
                onReshoot = { vm.flush(leaving = false) { onReshoot(drillingId, LampType.UF.name) } },
                onDelete = uf?.let { { pendingDeleteLayer = it.id } },
            )

            val depth by vm.depth.collectAsStateWithLifecycle()
            val depthBusy by vm.depthBusy.collectAsStateWithLifecycle()
            val depthMismatch by vm.depthMismatch.collectAsStateWithLifecycle()
            DepthEditor(
                depth = depth,
                busy = depthBusy,
                mismatch = depthMismatch,
                onChange = vm::onDepthChange,
                onApplyToAll = vm::applyDepthToAllLayers,
                onDiscard = vm::discardDepthDraft,
            )
            },
        )
    }

    if (showEditNumber && t != null) {
        EditNumberDialog(
            current = t.table.number,
            onDismiss = { showEditNumber = false },
            onConfirm = { n -> vm.updateNumber(n); showEditNumber = false },
        )
    }

    if (showDeleteTable) {
        AlertDialog(
            onDismissRequest = { showDeleteTable = false },
            title = { Text(stringResource(R.string.table_view_delete_table_title)) },
            text = { Text(stringResource(R.string.table_view_delete_table_message, t?.table?.number ?: "")) },
            confirmButton = {
                TextButton(onClick = { showDeleteTable = false; vm.deleteTable() }) {
                    Text(stringResource(R.string.common_delete), color = Red30)
                }
            },
            dismissButton = { TextButton(onClick = { showDeleteTable = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    pendingDeleteLayer?.let { layerId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteLayer = null },
            title = { Text(stringResource(R.string.table_view_delete_layer_title)) },
            text = { Text(stringResource(R.string.table_view_delete_layer_message)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteLayer(layerId); pendingDeleteLayer = null }) {
                    Text(stringResource(R.string.common_delete), color = Red30)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteLayer = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun EditNumberDialog(
    current: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.table_view_number_dialog_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { v -> text = v.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.table_view_number_field_label)) },
                singleLine = true,
                isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = valid) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun LayerRow(
    label: String,
    present: Boolean,
    labelColor: Color,
    onReshoot: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
    ) {
        Text(
            stringResource(
                R.string.layer_status,
                label,
                stringResource(if (present) R.string.layer_present else R.string.layer_absent),
            ),
            style = Body3Bold, color = labelColor, modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onReshoot) {
            Text(stringResource(if (present) R.string.table_view_reshoot else R.string.table_view_addshoot))
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.table_view_delete_layer_cd), tint = Red30)
            }
        }
    }
}

@Composable
private fun DepthEditor(
    depth: TableViewViewModel.DepthState,
    busy: Boolean,
    mismatch: Boolean,
    onChange: (DepthInput.Field, String) -> Unit,
    onApplyToAll: () -> Unit,
    onDiscard: () -> Unit,
) {
    // R15: текст полей — как введён (во ViewModel); неверный ввод не превращается в «пусто».
    val upperError = depth.errors[DepthInput.Field.UPPER]
    val lowerError = depth.errors[DepthInput.Field.LOWER]
    Text(stringResource(R.string.table_view_depth_hint), style = Body3, color = Gray40)
    if (depth.restoredDraft) {
        Text(stringResource(R.string.drilling_form_restored), style = Body3, color = Orange30)
        TextButton(onClick = onDiscard, enabled = !busy) { Text(stringResource(R.string.drilling_form_discard)) }
    }
    if (mismatch) {
        Text(stringResource(R.string.table_view_depth_mismatch), style = Body3, color = Orange30)
        OutlinedButton(onClick = onApplyToAll, enabled = depth.loaded && !busy) {
            Text(stringResource(R.string.table_view_depth_apply_all))
        }
    }
    // FIXES §10: длинные подписи — поля друг под другом на всю ширину (в портрете не обрезаются).
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
    ) {
        OutlinedTextField(
            value = depth.upper, onValueChange = { onChange(DepthInput.Field.UPPER, it) },
            enabled = depth.loaded && !busy,
            label = { Text(stringResource(R.string.table_view_depth_upper)) },
            isError = upperError != null,
            supportingText = upperError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = depth.lower, onValueChange = { onChange(DepthInput.Field.LOWER, it) },
            enabled = depth.loaded && !busy,
            label = { Text(stringResource(R.string.table_view_depth_lower)) },
            isError = lowerError != null,
            supportingText = lowerError?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    // R15: глубины сохраняются автоматически; при ошибке — черновик.
    Text(
        stringResource(
            if (depth.status == TableViewViewModel.DepthStatus.INVALID) R.string.table_view_depth_status_invalid
            else R.string.drilling_form_status_saved,
        ),
        style = Body3,
        color = if (depth.status == TableViewViewModel.DepthStatus.INVALID) Orange30 else Gray40,
    )
}
