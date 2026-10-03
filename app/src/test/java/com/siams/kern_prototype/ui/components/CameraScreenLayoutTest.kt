package com.siams.kern_prototype.ui.components

import android.util.Size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.siams.kern_prototype.ui.ComposeHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FIXES §8: кадр не исчезает в узком альбомном окне; в широком — две панели. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CameraScreenLayoutTest {

    private val host = ComposeHost()

    @get:Rule
    val rules = host.rules

    private fun show(width: Dp, height: Dp, panelWidth: Dp) {
        host.compose.setContent {
            Box(Modifier.size(width, height)) {
                val preview = rememberMovableCameraPreview(onSurfaceAvailable = {}, onSurfaceDestroyed = { false })
                CameraScreenLayout(
                    previewSize = Size(1500, 1000),
                    preview = preview,
                    panelWidth = panelWidth,
                    previewOverlay = {},
                    portraitOverlay = { Text("overlay", Modifier.testTag("portrait")) },
                    panelArrangement = Arrangement.Top,
                    panelAlignment = Alignment.Start,
                    panelPadding = 0.dp,
                    panel = { Text("panel", Modifier.testTag("panel")) },
                )
            }
        }
    }

    private fun preview() = host.compose.onNodeWithTag(TEST_TAG).getBoundsInRoot()

    @Test
    fun narrowLandscapeWindowKeepsPreviewInSingleColumn() {
        show(350.dp, 300.dp, panelWidth = 280.dp)
        val b = preview()
        assertEquals("кадр во всю ширину окна", 350f, b.width.value, 0.5f)
        assertEquals(233.3f, b.height.value, 0.5f)
        host.compose.onNodeWithTag("portrait").assertExists()
        host.compose.onNodeWithTag("panel").assertDoesNotExist()
    }

    @Test
    fun editorPanelInNarrowWindowAlsoFallsBackToSingleColumn() {
        show(560.dp, 300.dp, panelWidth = 360.dp)
        assertEquals("одна колонка: кадр 3:2 по высоте окна", 450f, preview().width.value, 0.5f)
        assertEquals(300f, preview().height.value, 0.5f)
        host.compose.onNodeWithTag("panel").assertDoesNotExist()
    }

    @Test
    fun wideWindowUsesTwoPanesWithVisiblePreview() {
        show(800.dp, 400.dp, panelWidth = 280.dp)
        val b = preview()
        assertTrue("кадр виден", b.width.value > 0f && b.height.value > 0f)
        assertEquals("кадр — в левой части (800 − 280)", 520f, b.width.value, 0.5f)
        assertTrue(b.right.value <= 520.5f)
        val panel = host.compose.onNodeWithTag("panel").getBoundsInRoot()
        assertTrue("панель справа от кадра", panel.left.value >= 519.5f)
        host.compose.onNodeWithTag("portrait").assertDoesNotExist()
        assertFalse(b.height.value > 400f)
    }
}
