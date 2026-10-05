package com.siams.kern_prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.siams.kern_prototype.data.db.DatabaseGate
import com.siams.kern_prototype.ui.nav.KernNavGraph
import com.siams.kern_prototype.ui.screens.AppRoot
import com.siams.kern_prototype.ui.screens.DatabaseErrorScreen
import com.siams.kern_prototype.ui.theme.KernTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Не зависит от графа БД — доступна, даже если БД открыть нельзя. */
    @Inject lateinit var databaseGate: DatabaseGate

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KernTheme {
                // Граф навигации (ViewModel с БД) — только при доступной БД; иначе экран ошибки.
                AppRoot(
                    checkDatabase = databaseGate::open,
                    ready = { KernNavGraph() },
                    failed = { reason -> DatabaseErrorScreen(reason, onClose = ::finishAffinity) },
                )
            }
        }
    }

    /**
     * R16/R19: singleTop — подключение USB-камеры при открытом приложении приходит сюда, а не
     * создаёт второй экземпляр Activity (с ещё одним экраном, претендующим на камеру).
     * Восстановление подключения запускает сам экран камеры ([com.siams.kern_prototype.camera.UsbDeviceEvents]).
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}
