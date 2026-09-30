package com.siams.kern_prototype.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Остановить корутины VM ДО `Dispatchers.resetMain()`: отменить и ДОЖДАТЬСЯ их завершения.
 * Одной отмены мало — корутина внутри транзакции Room/IO по завершении всё равно возвращается на
 * Main и может попасть в момент подмены диспетчера («Dispatchers.Main is used concurrently with
 * setting it»). Главный поток [main] при этом должен работать — на нём корутины и завершаются.
 */
internal fun stopViewModels(vms: Collection<ViewModel>, main: ExecutorService) {
    runBlocking {
        withTimeout(10_000) {
            vms.toList().forEach { it.viewModelScope.coroutineContext[Job]!!.cancelAndJoin() }
        }
    }
    main.submit {}.get(5, TimeUnit.SECONDS) // главный поток опустел
}
