package com.siams.kern_prototype

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.siams.kern_prototype.ui.nav.KernNavGraph
import com.siams.kern_prototype.ui.theme.KernTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KernTheme {
                KernNavGraph()
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
