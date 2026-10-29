package com.siams.kern_prototype.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.camera.CameraLimits
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.ui.components.rememberMovableCameraPreview
import com.siams.kern_prototype.ui.components.CameraScreenLayout
import com.siams.kern_prototype.ui.components.LampSwitch
import com.siams.kern_prototype.ui.components.applyWarningText
import com.siams.kern_prototype.ui.components.wbOpenWarningText
import com.siams.kern_prototype.ui.theme.Blue
import com.siams.kern_prototype.ui.theme.Blue40
import com.siams.kern_prototype.ui.theme.Body2Bold
import com.siams.kern_prototype.ui.theme.Body3Bold
import com.siams.kern_prototype.ui.theme.Body4
import com.siams.kern_prototype.ui.theme.Gray30
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.theme.Ink
import com.siams.kern_prototype.ui.theme.KernTheme
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Orange30
import com.siams.kern_prototype.viewmodel.AutoProc
import com.siams.kern_prototype.viewmodel.CameraProfilesViewModel
import kotlinx.coroutines.launch

@Composable
fun CameraProfilesScreen(
    onBack: () -> Unit,
    vm: CameraProfilesViewModel = hiltViewModel(),
) = KernTheme(dark = true) {
    val context = LocalContext.current
    // USB-камера Touptek подключается внутри контроллера (запрос USB-доступа — системный).
    LaunchedEffect(Unit) { vm.start() }
    // R16: уход в фон/возврат — явное отсоединение и подключение поверхности превью.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> vm.onStop()
                androidx.lifecycle.Lifecycle.Event.ON_START -> vm.onStart()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val lamp by vm.lamp.collectAsStateWithLifecycle()
    val profile by vm.working.collectAsStateWithLifecycle()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val showWarning by vm.showModifyWarning.collectAsStateWithLifecycle()
    val autoProc by vm.autoProc.collectAsStateWithLifecycle()
    val showFfcInstruction by vm.showFfcInstruction.collectAsStateWithLifecycle()
    val showImportWarning by vm.showImportWarning.collectAsStateWithLifecycle()
    val ffcRejection by vm.ffcRejection.collectAsStateWithLifecycle()
    val showFfcRemove by vm.showFfcRemove.collectAsStateWithLifecycle()
    val cameraState by vm.cameraState.collectAsStateWithLifecycle()
    val applyWarning by vm.applyWarning.collectAsStateWithLifecycle()
    val wbOpenMethod by vm.wbOpenMethod.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val exitPrompt by vm.exitPrompt.collectAsStateWithLifecycle()
    val exit by vm.exit.collectAsStateWithLifecycle()
    // R23/R13: выход — только через VM (несохранённые черновики, идущая автонастройка).
    androidx.activity.compose.BackHandler { vm.requestExit() }
    LaunchedEffect(exit) { if (exit) onBack() }
    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    var selected by remember { mutableIntStateOf(0) }
    // R13: процедуры — только при готовой камере (раньше без подключения считалось «поддерживается»).
    val cameraReady = cameraState is CameraControllerState.Ready
    val manualSupported = (cameraState as? CameraControllerState.Ready)?.manualSupported ?: true
    val busy = autoProc != AutoProc.NONE
    val limits by vm.limits.collectAsStateWithLifecycle()
    val capabilities = vm.capabilities
    val paramList = remember(limits, capabilities) { profileParams(limits, capabilities) }
    val spec = paramList[selected]

    // §4.2: экспорт/импорт профилей через системный выбор файла (SAF).
    val scope = rememberCoroutineScope()
    // JSON собирается ДО выбора файла: при ошибке (пропал эталон FFC) файл не создаётся вовсе.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val jsonText = pendingExport
        pendingExport = null
        if (uri != null && jsonText != null) scope.launch {
            val ok = runCatching {
                checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(jsonText.toByteArray()) }
            }.isSuccess
            android.widget.Toast.makeText(
                context,
                if (ok) "Профили экспортированы" else "Не удалось записать файл",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text != null) vm.stageImport(text) // покажет предупреждение (§4.1) перед применением
            else android.widget.Toast.makeText(context, "Не удалось прочитать файл", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // FIXES §8: превью переносится между компоновками без пересоздания TextureView.
    val preview = rememberMovableCameraPreview(vm::onSurfaceAvailable, vm::onSurfaceDestroyed)
    val previewSize = (cameraState as? CameraControllerState.Ready)?.previewSize

    // Плашки поверх превью: подключение, ошибка камеры (R16 — повтор без выхода).
    val previewOverlay: @Composable BoxScope.() -> Unit = {
    (cameraState as? CameraControllerState.Opening)?.let {
        Text(
            "Подключение к камере Touptek…",
            color = Color.White, style = Body2Bold,
            modifier = Modifier.align(Alignment.Center),
        )
    }
    // R16: ошибка/отключение камеры — повтор подключения без выхода с экрана.
    (cameraState as? CameraControllerState.Error)?.let { err ->
        Column(
            modifier = Modifier.align(Alignment.Center).padding(KernTokens.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
        ) {
            Text(err.message, color = Orange30, style = Body3Bold, textAlign = TextAlign.Center)
            OutlinedButton(onClick = vm::reconnect, enabled = autoProc == AutoProc.NONE) {
                Text(androidx.compose.ui.res.stringResource(com.siams.kern_prototype.R.string.camera_reconnect), color = Color.White)
            }
        }
    }
    }

    // Назад, заголовок, выбор профиля, автонастройка.
    val top: @Composable ColumnScope.() -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::requestExit, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color.White)
            }
            Text("Профиль камеры", style = Body2Bold, color = Color.White)
        }
        LampSwitch(selected = lamp, onSelect = vm::selectLamp, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        if (dirty.isNotEmpty()) {
            Text(
                "Не сохранено: " + dirty.sortedBy { it.ordinal }.joinToString { it.ruLabel },
                style = Body4, color = Orange30,
            )
        }

        // §1/§2: автоматические процедуры — визуально выделены и сверху.
        Text("Автонастройка", style = Body3Bold, color = Color.White)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
        ) {
            OutlinedButton(
                onClick = vm::runAutoExposure,
                enabled = !busy && cameraReady && manualSupported,
                modifier = Modifier.weight(1f),
            ) { Text("Автоэкспозиция") }
            OutlinedButton(
                onClick = vm::runAutoWhiteBalance,
                enabled = !busy && cameraReady && manualSupported,
                modifier = Modifier.weight(1f),
            ) { Text("Автобаланс") }
        }
        val hasFfc = profile.ffcTemplatePath.isNotBlank()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
        ) {
            OutlinedButton(
                onClick = vm::requestFfc,
                enabled = !busy && cameraReady && manualSupported,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (hasFfc) "Переснять эталон (FFC)" else "Коррекция освещения (FFC)")
            }
            // Удаление не требует камеры — только правка черновика профиля.
            if (hasFfc) {
                OutlinedButton(onClick = vm::requestFfcRemove, enabled = !busy) { Text("Удалить эталон") }
            }
        }
        if (hasFfc) {
            Text("Эталон FFC снят ✓ — применяется к снимкам", style = Body4, color = Blue40)
        }
        if (busy) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    when (autoProc) {
                        AutoProc.EXPOSURE -> "Подбор экспозиции…"
                        AutoProc.WHITE_BALANCE -> "Автобаланс белого…"
                        AutoProc.FLAT_FIELD -> "Съёмка эталона FFC…"
                        AutoProc.NONE -> ""
                    },
                    style = Body4, color = Color.White,
                )
            }
        }
        Text(lastModifiedLabel(saved.lastModifiedAt), style = Body4, color = Gray30)

        // Отказы SDK при применении профиля к текущему подключению — предупреждение, не блокировка.
        applyWarning?.let { Text(applyWarningText(it), style = Body4, color = Orange30) }
        // Камера открыта без режима RGB Gain: ручной WB и AWB не подтверждены (не блокировка).
        wbOpenWarningText(wbOpenMethod)?.let { Text(it, style = Body4, color = Orange30) }
        if (!manualSupported) {
            Text(
                "⚠ Ручной режим (MANUAL_SENSOR) не поддерживается — превью может не меняться (§6.1).",
                style = Body4, color = Orange30,
            )
        }
    }

    // Ползунок выбранного параметра, лента параметров, сохранение и перенос.
    val bottom: @Composable ColumnScope.() -> Unit = {
        Text(
            "${spec.label}: ${spec.format(spec.get(profile))}",
            style = Body3Bold, color = Color.White,
        )
        Slider(
            value = spec.toSlider(spec.get(profile)),
            valueRange = spec.sliderRange,
            onValueChange = { t -> vm.update(spec.set(profile, spec.fromSlider(t))) },
            // R13: не конкурирует с автонастройкой; R14: фиксированный диапазон камеры — не двигается.
            enabled = !busy && !spec.fixed,
        )
        // R14: сохранённое значение не переписывается — показываем, что уйдёт в SDK (только для
        // параметров, чьё отправляемое значение считается так же, как в контроллере).
        spec.sentToSdkHint(spec.get(profile))?.let { Text(it, style = Body4, color = Orange30) }
        spec.note?.let { Text(it, style = Body4, color = Gray40) }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
        ) {
            paramList.forEachIndexed { i, p ->
                ParamChip(
                    label = p.label,
                    value = p.format(p.get(profile)),
                    selected = i == selected,
                    onClick = { selected = i },
                )
            }
        }
        // §4.2: перенос профилей между фотокабинами.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        runCatching { vm.buildExportJson() }
                            .onSuccess {
                                pendingExport = it
                                exportLauncher.launch("kern_camera_profiles.json")
                            }
                            .onFailure {
                                android.widget.Toast.makeText(
                                    context,
                                    it.message ?: "Не удалось подготовить экспорт",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                            }
                    }
                },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Экспорт") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("Импорт") }
        }
        Button(onClick = vm::requestSave, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Сохранить профиль «${lamp.ruLabel}»")
        }
    }

    CameraScreenLayout(
        previewSize = previewSize,
        preview = preview,
        panelWidth = KernTokens.sidePanelWideWidth,
        previewOverlay = previewOverlay,
        portraitOverlay = {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .fillMaxWidth()
                    .padding(KernTokens.spaceMd)
                    // Плотнее, чем было (0.5): нижние строки панели ложатся на светлое превью.
                    .background(Ink.copy(alpha = 0.8f), KernTokens.radiusCard)
                    .padding(KernTokens.spaceMd),
                verticalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
                content = top,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .background(Ink.copy(alpha = 0.55f), KernTokens.radiusCard)
                    .padding(KernTokens.spaceLg),
                verticalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
                content = bottom,
            )
        },
        // Альбомная ориентация / планшет: параметры справа от кадра (прокрутка).
        panelArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
        panelAlignment = Alignment.Start,
        panelPadding = KernTokens.spaceMd,
        panel = {
            top()
            bottom()
        },
        modifier = Modifier.background(Ink),
    )

    // Подтверждения — вне ветвления компоновок: показываются в любой ориентации.
    ProfileEditorDialogs(
        profileEditorDialogs(
            lamp = lamp,
            dirty = dirty,
            showWarning = showWarning,
            showFfcInstruction = showFfcInstruction,
            exitPrompt = exitPrompt,
            showImportWarning = showImportWarning,
            ffcRejection = ffcRejection,
            showFfcRemove = showFfcRemove,
            actions = ProfileEditorDialogActions(
                confirmSave = vm::confirmSave,
                dismissWarning = vm::dismissWarning,
                confirmFfc = vm::confirmFfc,
                dismissFfc = vm::dismissFfc,
                saveAllAndExit = vm::saveAllAndExit,
                discardAndExit = vm::discardAndExit,
                cancelExit = vm::cancelExit,
                confirmImport = vm::confirmImport,
                dismissImport = vm::dismissImport,
                retryFfc = vm::retryFfc,
                dismissFfcRejection = vm::dismissFfcRejection,
                confirmFfcRemove = vm::confirmFfcRemove,
                dismissFfcRemove = vm::dismissFfcRemove,
            ),
        ),
    )
}

/** Подпись «последнее изменение профиля» (§4.1). */
private fun lastModifiedLabel(ts: Long): String =
    if (ts <= 0L) {
        "Профиль ещё не сохранялся (значения по умолчанию)"
    } else {
        val fmt = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.forLanguageTag("ru"))
        "Последнее изменение: ${fmt.format(java.util.Date(ts))}"
    }

@Composable
private fun ParamChip(
    label: String,
    value: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) Blue.copy(alpha = 0.25f) else Color.Transparent,
                KernTokens.radiusChip,
            )
            .border(
                1.dp,
                if (selected) Blue else Gray40,
                KernTokens.radiusChip,
            )
            .padding(horizontal = KernTokens.spaceMd, vertical = KernTokens.spaceSm),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = Body3Bold, color = Color.White, textAlign = TextAlign.Center)
        Text(label, style = Body4, color = if (selected) Color.White else Gray40, textAlign = TextAlign.Center)
    }
}
