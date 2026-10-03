package com.siams.kern_prototype.ui.components

import android.util.Size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.siams.kern_prototype.ui.ComposeHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FIXES §1: превью вписано с сохранением пропорций кадра и по центру. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraPreviewTest {

    private val host = ComposeHost()

    @get:Rule
    val rules = host.rules

    private fun bounds() = host.compose.onNodeWithTag(TEST_TAG).getBoundsInRoot()

    @Test
    fun wideFrameInTallAreaKeepsAspectAndIsCentered() {
        host.compose.setContent {
            Box(Modifier.size(300.dp, 400.dp)) {
                CameraPreview(Size(1500, 1000), onSurfaceAvailable = {}, onSurfaceDestroyed = { false })
            }
        }
        val b = bounds()
        assertEquals(300f, b.width.value, 0.5f)
        assertEquals("3:2 → высота 200", 200f, b.height.value, 0.5f)
        assertEquals("по центру", 100f, b.top.value, 0.5f)
    }

    @Test
    fun wideAreaFrameUsesFullHeight() {
        host.compose.setContent {
            Box(Modifier.size(300.dp, 180.dp)) {
                CameraPreview(Size(1500, 1000), onSurfaceAvailable = {}, onSurfaceDestroyed = { false })
            }
        }
        val b = bounds()
        assertEquals(180f, b.height.value, 0.5f)
        assertEquals(270f, b.width.value, 0.5f)
        assertEquals(15f, b.left.value, 0.5f)
    }

    /** Размер ещё неизвестен (подключение) — сохраняется последний известный, а не растяжение. */
    @Test
    fun keepsLastKnownSizeWhileUnknown() {
        val size = mutableStateOf<Size?>(Size(1500, 1000))
        host.compose.setContent {
            Box(Modifier.size(300.dp, 400.dp)) {
                CameraPreview(size.value, onSurfaceAvailable = {}, onSurfaceDestroyed = { false })
            }
        }
        host.compose.runOnIdle { size.value = null }
        assertEquals(200f, bounds().height.value, 0.5f)
    }
}
