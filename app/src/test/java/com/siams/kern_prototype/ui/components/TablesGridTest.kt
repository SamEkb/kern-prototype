package com.siams.kern_prototype.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.siams.kern_prototype.ui.ComposeHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FIXES §8: сетка столов — две колонки на обычном телефоне, больше на широком экране. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w900dp-h900dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TablesGridTest {

    private val host = ComposeHost()

    @get:Rule
    val rules = host.rules

    /** Число колонок фактической сетки: карточки в первой строке. */
    private fun columnsAt(widthDp: Int): Int {
        host.compose.setContent {
            Box(Modifier.size(widthDp.dp, 800.dp)) {
                TablesGrid { items((0 until 8).toList()) { i -> Box(Modifier.aspectRatio(1f).testTag("card$i")) } }
            }
        }
        val top0 = host.compose.onNodeWithTag("card0").getUnclippedBoundsInRoot().top
        return (0 until 8).count { host.compose.onNodeWithTag("card$it").getUnclippedBoundsInRoot().top == top0 }
    }

    @Test
    fun twoColumnsOn360dpPhone() {
        assertEquals(2, columnsAt(360))
        assertEquals("карточка: (360 − 2×16 − 12) / 2", 158f, host.compose.onNodeWithTag("card0").getUnclippedBoundsInRoot().width.value, 0.5f)
    }

    @Test
    fun twoColumnsOn320dpPhone() = assertEquals(2, columnsAt(320))

    @Test
    fun moreColumnsOnWideScreen() = assertEquals("(900 − 32 + 12) / (136 + 12)", 5, columnsAt(900))
}
