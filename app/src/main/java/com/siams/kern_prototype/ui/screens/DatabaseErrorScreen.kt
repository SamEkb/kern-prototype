package com.siams.kern_prototype.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.siams.kern_prototype.R
import com.siams.kern_prototype.data.db.DatabaseGate
import com.siams.kern_prototype.ui.components.readableWidth
import com.siams.kern_prototype.ui.theme.Body3
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Red30
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Корень приложения: сначала — состояние БД ([DatabaseGate], в фоне). Содержимое [ready] (граф
 * навигации и всё, что держит БД) составляется ТОЛЬКО при доступной БД; иначе — [failed] с
 * причиной. Пока проверка идёт — пустой фон.
 */
@Composable
fun AppRoot(
    checkDatabase: suspend () -> DatabaseGate.State,
    ready: @Composable () -> Unit,
    failed: @Composable (String) -> Unit,
) {
    val state by produceState<DatabaseGate.State?>(null) { value = withContext(Dispatchers.IO) { checkDatabase() } }
    when (val s = state) {
        null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        DatabaseGate.State.Ready -> ready()
        is DatabaseGate.State.Failed -> failed(s.message)
    }
}

/**
 * Ошибка открытия БД: причина и что делать. Не зависит от графа БД; данные не удаляются и не
 * подменяются — работа с архивом (съёмка, отправка) недоступна до устранения причины.
 */
@Composable
fun DatabaseErrorScreen(reason: String, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(KernTokens.spaceLg)
            .readableWidth(),
        verticalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
    ) {
        Text(stringResource(R.string.db_error_title), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.db_error_body), style = Body3)
        Text(stringResource(R.string.db_error_reason, reason), style = Body3, color = Red30)
        Button(onClick = onClose) { Text(stringResource(R.string.db_error_close)) }
    }
}
