package com.siams.kern_prototype.ui.screens

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.ui.components.LampSwitch
import com.siams.kern_prototype.ui.theme.Blue
import com.siams.kern_prototype.ui.theme.Body2Bold
import com.siams.kern_prototype.ui.theme.Body3Bold
import com.siams.kern_prototype.ui.theme.Body4
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.theme.Ink
import com.siams.kern_prototype.ui.theme.KernTheme
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Orange30
import com.siams.kern_prototype.viewmodel.AutoProc
import com.siams.kern_prototype.viewmodel.CameraProfilesViewModel
import kotlinx.coroutines.launch

/** Один редактируемый параметр профиля (лейбл, диапазон, чтение/запись, формат значения). */
private data class ParamSpec(
    val label: String,
    val min: Float,
    val max: Float,
    val get: (CameraProfile) -> Float,
    val set: (CameraProfile, Float) -> CameraProfile,
    val format: (Float) -> String,
)

private val PARAMS: List<ParamSpec> = listOf(
    ParamSpec(
        "Выдержка", 0.5f, 500f,
        get = { it.exposureTimeNs / 1_000_000f },
        set = { p, v -> p.copy(exposureTimeNs = (v * 1_000_000).toLong()) },
        format = { "%.1f мс".format(it) },
    ),
    ParamSpec(
        "Усиление", 25f, 3200f,   // §3.2 Gain (аналог ISO); хранится в поле iso
        get = { it.iso.toFloat() },
        set = { p, v -> p.copy(iso = v.toInt()) },
        format = { "%d".format(it.toInt()) },
    ),
    ParamSpec(
        "Гамма", 20f, 180f,       // §3.3 Gamma, проценты (100 = 1.0); критична для УФ
        get = { it.gamma.toFloat() },
        set = { p, v -> p.copy(gamma = v.toInt()) },
        format = { "%d%%".format(it.toInt()) },
    ),
    ParamSpec(
        "Фокус", 0f, 10f,
        get = { it.focusDistance },
        set = { p, v -> p.copy(focusDistance = v) },
        format = { if (it < 0.05f) "∞" else "%.1f дптр".format(it) },
    ),
    ParamSpec(
        "WB R", 1f, 4f,
        get = { it.wbRedGain },
        set = { p, v -> p.copy(wbRedGain = v) },
        format = { "%.2f".format(it) },
    ),
    ParamSpec(
        "WB G", 1f, 4f,
        get = { it.wbGreenEvenGain },
        set = { p, v -> p.copy(wbGreenEvenGain = v, wbGreenOddGain = v) },
        format = { "%.2f".format(it) },
    ),
    ParamSpec(
        "WB B", 1f, 4f,
        get = { it.wbBlueGain },
        set = { p, v -> p.copy(wbBlueGain = v) },
        format = { "%.2f".format(it) },
    ),
)

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
    val cameraState by vm.cameraState.collectAsStateWithLifecycle()
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
    val spec = PARAMS[selected]

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

    Box(Modifier.fillMaxSize().background(Ink)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) =
                            vm.onSurfaceAvailable(s)

                        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                        // R16: текстуру освобождает VM — после того, как камера перестала на неё рисовать.
                        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean =
                            vm.onSurfaceDestroyed(s)

                        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                    }
                }
            },
        )
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

        // Верх: назад, заголовок, выбор редактируемого профиля.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(KernTokens.spaceMd)
                .background(Ink.copy(alpha = 0.5f), KernTokens.radiusCard)
                .padding(KernTokens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
        ) {
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
            OutlinedButton(
                onClick = vm::requestFfc,
                enabled = !busy && cameraReady && manualSupported,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (profile.ffcTemplatePath.isNotBlank()) "Переснять эталон (FFC)" else "Коррекция освещения (FFC)")
            }
            if (profile.ffcTemplatePath.isNotBlank()) {
                Text("Эталон FFC снят ✓ — применяется к снимкам", style = Body4, color = Blue)
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
            Text(lastModifiedLabel(saved.lastModifiedAt), style = Body4, color = Gray40)

            if (!manualSupported) {
                Text(
                    "⚠ Ручной режим (MANUAL_SENSOR) не поддерживается — превью может не меняться (§6.1).",
                    style = Body4, color = Orange30,
                )
            }
        }

        // Низ: слайдер выбранного параметра, лента параметров, сохранение.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .background(Ink.copy(alpha = 0.55f), KernTokens.radiusCard)
                .padding(KernTokens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
        ) {
            Text(
                "${spec.label}: ${spec.format(spec.get(profile))}",
                style = Body3Bold, color = Color.White,
            )
            Slider(
                value = spec.get(profile).coerceIn(spec.min, spec.max),
                valueRange = spec.min..spec.max,
                onValueChange = { v -> vm.update(spec.set(profile, v)) },
                enabled = !busy, // R13: ползунок не конкурирует с автонастройкой
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceSm),
            ) {
                PARAMS.forEachIndexed { i, p ->
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

        // §4.1: предупреждение о несопоставимости снимков при изменении уже сохранённого профиля.
        if (showWarning) {
            AlertDialog(
                onDismissRequest = vm::dismissWarning,
                title = { Text("Изменить сохранённый профиль?") },
                text = {
                    Text(
                        "Изменение настроек профиля «${lamp.ruLabel}» может привести к тому, что порода " +
                            "будет выглядеть иначе на новых снимках по сравнению с уже отснятыми материалами. " +
                            "Это может нарушить сопоставимость снимков внутри долбления.\n\n" +
                            "Сохранить изменения?",
                    )
                },
                confirmButton = { TextButton(onClick = vm::confirmSave) { Text("Сохранить") } },
                dismissButton = { TextButton(onClick = vm::dismissWarning) { Text("Отмена") } },
            )
        }

        // §2.3: инструкция перед съёмкой эталона FFC.
        if (showFfcInstruction) {
            AlertDialog(
                onDismissRequest = vm::dismissFfc,
                title = { Text("Коррекция освещения (FFC)") },
                text = {
                    Text(
                        "Поместите в фотокабину лист белой бумаги или иной равномерный белый фон так, " +
                            "чтобы он заполнял весь кадр. После нажатия «Снять эталон» будет сделан " +
                            "эталонный снимок для профиля «${lamp.ruLabel}» — по нему все последующие " +
                            "снимки керна будут выравниваться по освещению.",
                    )
                },
                confirmButton = { TextButton(onClick = vm::confirmFfc) { Text("Снять эталон") } },
                dismissButton = { TextButton(onClick = vm::dismissFfc) { Text("Отмена") } },
            )
        }

        // R23: несохранённые черновики при выходе.
        exitPrompt?.let { lamps ->
            AlertDialog(
                onDismissRequest = vm::cancelExit,
                title = { Text("Сохранить изменения профилей?") },
                text = {
                    Text(
                        "Не сохранены изменения профилей: " +
                            lamps.sortedBy { it.ordinal }.joinToString { "«${it.ruLabel}»" } + ". " +
                            "Сохранение изменит профили, по которым идёт съёмка: новые снимки могут " +
                            "отличаться от уже отснятых.",
                    )
                },
                confirmButton = { TextButton(onClick = vm::saveAllAndExit) { Text("Сохранить всё") } },
                dismissButton = {
                    Row {
                        TextButton(onClick = vm::discardAndExit) { Text("Не сохранять") }
                        TextButton(onClick = vm::cancelExit) { Text("Остаться") }
                    }
                },
            )
        }

        // §4.2: то же предупреждение о несопоставимости снимков перед импортом профилей.
        if (showImportWarning) {
            AlertDialog(
                onDismissRequest = vm::dismissImport,
                title = { Text("Импортировать профили?") },
                text = {
                    Text(
                        "Импорт заменит текущие профили камеры (дневной и УФ) параметрами из файла. " +
                            "Это может привести к тому, что порода будет выглядеть иначе на новых снимках " +
                            "по сравнению с уже отснятыми материалами, и нарушить их сопоставимость.\n\n" +
                            (if (dirty.isNotEmpty()) "Несохранённые изменения будут потеряны: " +
                                dirty.sortedBy { it.ordinal }.joinToString { it.ruLabel } + ".\n\n" else "") +
                            "Применить импортированные профили?",
                    )
                },
                confirmButton = { TextButton(onClick = vm::confirmImport) { Text("Импортировать") } },
                dismissButton = { TextButton(onClick = vm::dismissImport) { Text("Отмена") } },
            )
        }
    }
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
