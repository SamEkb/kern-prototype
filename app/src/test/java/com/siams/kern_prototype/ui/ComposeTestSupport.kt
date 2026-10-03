package com.siams.kern_prototype.ui

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.robolectric.Shadows.shadowOf

/**
 * Compose-тесты под Robolectric без `unitTests.isIncludeAndroidResources` (она переводит SQLite
 * тестов на нативный WAL — см. этап 5e): хост-активность регистрируется в PackageManager до запуска
 * правила Compose. Подходит для компонентов без строковых ресурсов приложения.
 */
internal class ComposeHost {
    val compose: ComposeContentTestRule = createComposeRule()

    private val registerActivity = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
        }
    }

    val rules: RuleChain = RuleChain.outerRule(registerActivity).around(compose)
}
