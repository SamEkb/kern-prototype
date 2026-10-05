package com.siams.kern_prototype.ui.screens

import androidx.compose.material3.Text
import androidx.compose.ui.test.onNodeWithText
import com.siams.kern_prototype.data.db.DatabaseGate
import com.siams.kern_prototype.ui.ComposeHost
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Ошибка открытия БД: содержимое, держащее БД, не составляется; показывается экран ошибки. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppRootTest {

    private val host = ComposeHost()

    @get:Rule
    val rules = host.rules

    @Test
    fun failedDatabaseShowsErrorAndNeverComposesReadyContent() {
        var readyComposed = 0
        host.compose.setContent {
            AppRoot(
                checkDatabase = { DatabaseGate.State.Failed("Файл базы данных повреждён") },
                ready = { readyComposed++; Text("граф") },
                failed = { reason -> Text("ошибка: $reason") },
            )
        }
        host.compose.waitUntil(3_000) { host.compose.onAllNodes(androidx.compose.ui.test.hasText("ошибка", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        host.compose.onNodeWithText("ошибка: Файл базы данных повреждён").assertExists()
        assertEquals("граф навигации с БД не составлялся", 0, readyComposed)
    }

    @Test
    fun nothingDatabaseDependentBeforeCheckCompletesThenReady() {
        val gate = CompletableDeferred<DatabaseGate.State>()
        var readyComposed = 0
        host.compose.setContent {
            AppRoot(checkDatabase = { gate.await() }, ready = { readyComposed++; Text("граф") }, failed = { Text("ошибка") })
        }
        host.compose.waitForIdle()
        assertEquals("до проверки — ничего", 0, readyComposed)
        gate.complete(DatabaseGate.State.Ready)
        host.compose.waitUntil(3_000) { readyComposed > 0 }
        host.compose.onNodeWithText("граф").assertExists()
    }
}
