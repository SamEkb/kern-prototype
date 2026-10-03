package com.siams.kern_prototype.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.siams.kern_prototype.ui.ComposeHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FIXES §8: ширина списков/форм и экран стола в две панели. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveWidthTest {

    private val host = ComposeHost()

    @get:Rule
    val rules = host.rules

    private fun bounds(tag: String) = host.compose.onNodeWithTag(tag).getBoundsInRoot()

    @Test
    fun formIsLimitedAndCenteredOnWideScreen() {
        host.compose.setContent {
            Box(Modifier.size(900.dp, 400.dp)) { Box(Modifier.readableWidth().height(10.dp).testTag("c")) }
        }
        val b = bounds("c")
        assertEquals(640f, b.width.value, 0.5f)
        assertEquals("по центру", 130f, b.left.value, 0.5f)
    }

    @Test
    fun formUsesFullWidthOnPhone() {
        host.compose.setContent {
            Box(Modifier.size(360.dp, 400.dp)) { Box(Modifier.readableWidth().height(10.dp).testTag("c")) }
        }
        assertEquals(360f, bounds("c").width.value, 0.5f)
    }

    private fun split(w: Int, h: Int) {
        host.compose.setContent {
            Box(Modifier.size(w.dp, h.dp)) {
                AdaptiveSplit(
                    sideWidth = 360.dp,
                    mainAspect = 4f / 3f,
                    main = { m -> Box(m.testTag("main")) },
                    side = { Text("side", Modifier.testTag("side")) },
                )
            }
        }
    }

    @Test
    fun wideWindowPutsImageLeftAndPanelRight() {
        split(900, 500)
        val main = bounds("main")
        val side = bounds("side")
        assertEquals("изображение: 900 − 360 − отступы 2×16", 508f, main.width.value, 0.5f)
        assertEquals(468f, main.height.value, 0.5f)
        assertTrue("панель справа", side.left.value >= 540f)
    }

    @Test
    fun phoneStacksImageAndPanel() {
        split(360, 640)
        val main = bounds("main")
        val side = bounds("side")
        assertEquals(328f, main.width.value, 0.5f)
        assertEquals("4:3", 246f, main.height.value, 0.5f)
        assertTrue("панель под изображением", side.top.value >= main.bottom.value)
    }

    /** Узкое альбомное окно: изображению не осталось бы места рядом с панелью — одна колонка. */
    @Test
    fun narrowLandscapeTableFallsBackToSingleColumn() {
        split(500, 300)
        val main = bounds("main")
        assertEquals("изображение во всю ширину", 468f, main.width.value, 0.5f)
        // Панель ниже видимой области прокрутки — сравниваются необрезанные границы.
        val mainFull = host.compose.onNodeWithTag("main").getUnclippedBoundsInRoot()
        val sideFull = host.compose.onNodeWithTag("side").getUnclippedBoundsInRoot()
        assertTrue("панель под изображением", sideFull.top.value >= mainFull.bottom.value)
    }
}
