package com.siams.kern_prototype.ui.screens

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siams.kern_prototype.R
import com.siams.kern_prototype.camera.CameraControllerState
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.ui.components.LampSwitch
import com.siams.kern_prototype.ui.theme.Body2Bold
import com.siams.kern_prototype.ui.theme.Body3
import com.siams.kern_prototype.ui.theme.Body4
import com.siams.kern_prototype.ui.theme.Ink
import com.siams.kern_prototype.ui.theme.KernTheme
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Orange30
import com.siams.kern_prototype.ui.theme.UvYellow
import com.siams.kern_prototype.viewmodel.CaptureViewModel

@Composable
fun CaptureScreen(
    onBack: () -> Unit,
    vm: CaptureViewModel = hiltViewModel(),
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
    val tableNumber by vm.tableNumber.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val profile by vm.activeProfile.collectAsStateWithLifecycle()
    val cameraState by vm.cameraState.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    // Системный «Назад» во время снимка не закрывает экран (кадр не должен потеряться).
    androidx.activity.compose.BackHandler(enabled = capturing) {}

    LaunchedEffect(message) {
        message?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show()
            vm.consumeMessage()
        }
    }

    val capturedLamps by vm.capturedLamps.collectAsStateWithLifecycle()
    val manualSupported = (cameraState as? CameraControllerState.Ready)?.manualSupported ?: true

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
        // Плашка статуса подключения USB-камеры поверх превью.
        (cameraState as? CameraControllerState.Opening)?.let {
            Text(
                stringResource(R.string.capture_usb_connecting),
                color = Color.White, style = Body2Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        // Верхний HUD: стол, тип освещения, предупреждение о ручном режиме.
        // Полупрозрачная подложка — читаемость белого текста поверх светлых сцен.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(KernTokens.spaceMd)
                .background(Ink.copy(alpha = 0.45f), KernTokens.radiusCard)
                .padding(KernTokens.spaceMd),
            verticalArrangement = Arrangement.spacedBy(KernTokens.spaceXs),
        ) {
            // FIXES §6: отметка «снят» — на переключателе освещения; здесь только номер стола.
            Text(stringResource(R.string.table_number, tableNumber), style = Body2Bold, color = Color.White)
            Text(profileLine(profile, vm.controller.capabilities.supportsFocus), style = Body4, color = Color.White.copy(alpha = 0.85f))
            if (!manualSupported) {
                Text(
                    stringResource(R.string.capture_manual_unsupported),
                    style = Body4, color = Orange30,
                )
            }
            (cameraState as? CameraControllerState.Error)?.let {
                Text(stringResource(R.string.capture_camera_error, it.message), style = Body4, color = Orange30)
                // R16: повтор подключения без выхода с экрана.
                OutlinedButton(onClick = vm::reconnect, enabled = !capturing) {
                    Text(stringResource(R.string.camera_reconnect), color = Color.White)
                }
            }
        }

        // Нижний HUD: переключатель, спуск, новый стол, назад.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .fillMaxWidth()
                .padding(KernTokens.spaceLg),
            verticalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // R10/FIXES §7: во время снимка освещение, новый стол и выход недоступны.
            LampSwitch(
                selected = lamp, onSelect = vm::switchLamp, modifier = Modifier.fillMaxWidth(), enabled = !capturing,
                // Слои стола из Room: «снят» — только после фиксации слоя, не после нажатия Snap.
                captured = capturedLamps,
            )

            Button(
                onClick = vm::capture,
                // Ready — обычная съёмка; Error(canRetry) — снимок сначала переподключит камеру.
                enabled = !capturing && (
                    cameraState is CameraControllerState.Ready ||
                        (cameraState as? CameraControllerState.Error)?.canRetry == true
                    ),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(),
                modifier = Modifier.size(84.dp),
            ) {
                if (capturing) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(36.dp))
                } else {
                    Icon(Icons.Outlined.Camera, contentDescription = stringResource(R.string.capture_shoot_cd), modifier = Modifier.size(36.dp))
                }
            }
            if (capturing) {
                Text(stringResource(R.string.capture_in_progress), style = Body3, color = Color.White)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
            ) {
                OutlinedButton(onClick = onBack, enabled = !capturing, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text(stringResource(R.string.capture_done))
                }
                OutlinedButton(onClick = vm::newTable, enabled = !capturing, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text(stringResource(R.string.capture_new_table))
                }
            }
        }
    }
}

/** Строка параметров профиля в HUD; фокус — только если камера его применяет (FIXES §3). */
internal fun profileLine(p: CameraProfile, supportsFocus: Boolean): String {
    val shutter = if (p.exposureTimeNs > 0) "1/${(1_000_000_000.0 / p.exposureTimeNs).toInt()}с" else "—"
    val base = "Выдержка $shutter · ISO ${p.iso} · WB ${p.wbRedGain}/${p.wbBlueGain}"
    if (!supportsFocus) return base
    val focus = if (p.focusDistance == 0f) "∞" else String.format("%.2f", p.focusDistance)
    return "$base · Фокус $focus"
}
