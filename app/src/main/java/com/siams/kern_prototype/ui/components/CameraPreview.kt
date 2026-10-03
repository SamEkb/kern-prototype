package com.siams.kern_prototype.ui.components

import android.graphics.SurfaceTexture
import android.util.Size
import android.view.TextureView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView

/**
 * FIXES §1: превью камеры без искажения — кадр вписан в доступную область с сохранением пропорций
 * (поля по краям, как в просмотре стола). Одна реализация для съёмки и редактора профилей.
 *
 *  - [previewSize] — размер кадра превью камеры (из [com.siams.kern_prototype.camera.CameraControllerState.Ready]);
 *    null — ещё неизвестен: используется последний известный, а до первого — вся область.
 *  - `AndroidView` всегда в одной позиции композиции: при смене размера меняется только модификатор,
 *    `TextureView` (и её поверхность, R16) не пересоздаётся.
 */
@Composable
fun CameraPreview(
    previewSize: Size?,
    onSurfaceAvailable: (SurfaceTexture) -> Unit,
    onSurfaceDestroyed: (SurfaceTexture) -> Boolean,
    modifier: Modifier = Modifier,
) {
    var lastSize by remember { mutableStateOf<Size?>(null) }
    if (previewSize != null && previewSize.width > 0 && previewSize.height > 0) lastSize = previewSize
    val ratio = lastSize?.let { it.width.toFloat() / it.height }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = (if (ratio != null) Modifier.aspectRatio(ratio) else Modifier.fillMaxSize()).testTag(TEST_TAG),
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) = onSurfaceAvailable(s)
                        override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
                        // R16: текстуру освобождает VM — после того, как камера перестала на неё рисовать.
                        override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean = onSurfaceDestroyed(s)
                        override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
                    }
                }
            },
        )
    }
}

internal const val TEST_TAG = "camera-preview"

/**
 * FIXES §8: превью, переносимое между компоновками (одна панель ⇄ две панели) без пересоздания
 * `TextureView` ([movableContentOf]). Если пересоздание всё же произойдёт (смена `Activity`),
 * поверхность заново подключается к тому же контроллеру — R16 ([com.siams.kern_prototype.viewmodel.CameraScreenSession]).
 */
@Composable
fun rememberMovableCameraPreview(
    onSurfaceAvailable: (SurfaceTexture) -> Unit,
    onSurfaceDestroyed: (SurfaceTexture) -> Boolean,
): @Composable (Size?) -> Unit = remember {
    movableContentOf { size: Size? -> CameraPreview(size, onSurfaceAvailable, onSurfaceDestroyed) }
}
