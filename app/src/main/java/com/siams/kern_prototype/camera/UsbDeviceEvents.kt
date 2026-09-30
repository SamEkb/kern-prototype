package com.siams.kern_prototype.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * R16: системные события подключения USB-устройств. Экран камеры по ним запускает
 * восстановление, если камера в ошибке (отключили и подключили снова). Какое это устройство —
 * решает само подключение (поиск камеры Touptek по модели).
 */
@Singleton
open class UsbDeviceEvents @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    open val attached: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) trySend(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }
}
