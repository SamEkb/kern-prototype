package com.siams.kern_prototype.ui.components

import android.util.Size
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** FIXES §8: правило двух панелей; превью переживает смену компоновки без пересоздания TextureView. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveLayoutTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()
    private val registerActivity = object : ExternalResource() {
        override fun before() {
            val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(android.content.ComponentName(app, ComponentActivity::class.java))
        }
    }

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(registerActivity).around(compose)

    @Test
    fun twoPaneInLandscapeOrOnWideScreens() {
        assertFalse("телефон, портрет", useTwoPane(411f, 891f))
        assertTrue("телефон, альбомная", useTwoPane(891f, 411f))
        assertTrue("планшет, портрет", useTwoPane(800f, 1280f))
        assertFalse("узкий квадрат", useTwoPane(500f, 500f))
        assertFalse("узкое альбомное окно: кадру не осталось места", useTwoPane(350f, 300f))
        assertFalse("редактор (панель 360): кадру меньше 240", useTwoPane(560f, 300f, 360f))
        assertTrue(useTwoPane(600f, 300f, 360f))
    }

    private fun textureViews(v: View): List<TextureView> = when (v) {
        is TextureView -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { textureViews(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Test
    fun previewSurvivesLayoutSwitchWithoutRecreatingTextureView() {
        val twoPane = mutableStateOf(false)
        compose.setContent {
            val preview = rememberMovableCameraPreview(onSurfaceAvailable = {}, onSurfaceDestroyed = { false })
            if (twoPane.value) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) { preview(Size(1500, 1000)) }
                    Box(Modifier.width(100.dp))
                }
            } else {
                Box(Modifier.fillMaxSize()) { preview(Size(1500, 1000)) }
            }
        }
        compose.waitForIdle()
        val root = compose.activity.window.decorView
        val before = textureViews(root).single()
        compose.runOnIdle { twoPane.value = true }
        compose.waitForIdle()
        val after = textureViews(root).single()
        assertSame("тот же TextureView после смены компоновки", before, after)
        compose.runOnIdle { twoPane.value = false }
        compose.waitForIdle()
        assertSame(before, textureViews(root).single())
    }

    @Test
    fun manifestAllowsRotationWithoutRecreatingActivity() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertFalse("ориентация не зафиксирована", manifest.contains("android:screenOrientation"))
        val changes = Regex("android:configChanges=\"([^\"]+)\"").find(manifest)?.groupValues?.get(1)
        assertNotNull(changes)
        assertEquals(
            setOf("orientation", "screenSize", "screenLayout", "smallestScreenSize", "keyboardHidden"),
            changes!!.split('|').toSet(),
        )
    }
}
