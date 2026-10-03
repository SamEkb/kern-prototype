package com.siams.kern_prototype.viewmodel

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.data.settings.TestSecrets
import com.siams.kern_prototype.domain.model.ServerSettings
import com.siams.kern_prototype.network.ConnectionTester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

/** R20: при недоступном пароле экран настроек не входит с пустым паролем и не удаляет пароль. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsViewModelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val main = Executors.newSingleThreadExecutor()
    private val dir = File(context.cacheDir, "settings-vm-${System.nanoTime()}").apply { mkdirs() }
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val secretsData = PreferenceDataStoreFactory.create(scope = io) { File(dir, "s.preferences_pb") }
    private val store = ServerSettingsStore(
        PreferenceDataStoreFactory.create(scope = io) { File(dir, "k.preferences_pb") }, secretsData, TestSecrets.cipher(),
    )
    private var clients = 0
    private val tester = ConnectionTester().apply { clientFactory = { clients++; OkHttpClient() } }
    private val vms = java.util.Collections.synchronizedList(mutableListOf<androidx.lifecycle.ViewModel>())
    private lateinit var vm: SettingsViewModel

    private fun waitUntil(ms: Long = 5000, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(5) }
        throw AssertionError("условие не выполнено")
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(main.asCoroutineDispatcher())
        runBlocking {
            store.save(ServerSettings(host = "a.example", login = "user", password = "p"))
            secretsData.edit { it.clear() }                   // восстановление без файла секретов
        }
        vm = main.submit<SettingsViewModel> { SettingsViewModel(store, tester).also { vms += it } }.get()
    }

    @After
    fun tearDown() {
        stopViewModels(vms, main)
        Dispatchers.resetMain()
        main.shutdown()
        io.cancel()
        dir.deleteRecursively()
    }

    /** Форма экрана: пароль не введён заново (поле пустое). */
    private fun form(password: String = "") = ServerSettings(host = "a.example", login = "user", password = password)

    @Test
    fun connectionTestRefusesWithoutNetworkUntilPasswordReentered() {
        main.submit { vm.testConnection(form()) }.get()
        waitUntil { vm.test.value is TestState.Failure }
        assertTrue((vm.test.value as TestState.Failure).message.contains("введите его заново"))
        assertEquals("сетевых запросов не было", 0, clients)
        assertTrue("пароль не удалён автосохранением", runBlocking { store.settings.first() }.passwordUnavailable)
    }

    @Test
    fun saveWithEmptyFieldDoesNotDeleteUnavailablePassword() {
        main.submit { vm.save(form().copy(host = "b.example")) }.get()
        waitUntil { runBlocking { store.settings.first() }.host == "b.example" }
        assertTrue("по-прежнему «недоступен», а не намеренно пустой", runBlocking { store.settings.first() }.passwordUnavailable)

        main.submit { vm.save(form(password = "новый").copy(host = "b.example")) }.get()
        waitUntil { runBlocking { store.settings.first() }.password == "новый" }
        assertFalse(runBlocking { store.settings.first() }.passwordUnavailable)
    }
}
