package com.siams.kern_prototype.camera

import android.Manifest
import android.app.Application
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.settings.CameraProfileStore
import com.siams.kern_prototype.domain.model.LampType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowCameraCharacteristics

/** Lint MissingPermission (Camera2, запасной путь): без разрешения CAMERA — понятная ошибка, а не падение. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CameraControllerPermissionTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private lateinit var controller: CameraController

    @Before
    fun setUp() {
        val chars = ShadowCameraCharacteristics.newCameraCharacteristics()
        shadowOf(chars).set(CameraCharacteristics.LENS_FACING, CameraCharacteristics.LENS_FACING_BACK)
        shadowOf(app.getSystemService(CameraManager::class.java)).addCamera("0", chars)
        controller = CameraController(app, CameraProfileStore(app, json))
    }

    @After
    fun tearDown() = controller.release()

    @Test
    fun withoutCameraPermissionOpenFailsWithClearError() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        try {
            withTimeout(5_000) { controller.open(LampType.DAYLIGHT) }
            fail("без разрешения камера не должна открываться")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("Нет разрешения"))
        }
        val state = controller.state.value
        assertTrue("$state", state is CameraControllerState.Error && state.message.contains("Нет разрешения"))
    }

    @Test
    fun withCameraPermissionOpenProceeds() = runBlocking {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        withTimeout(5_000) { controller.open(LampType.DAYLIGHT) }
        assertTrue("${controller.state.value}", controller.state.value is CameraControllerState.Ready)
    }
}
