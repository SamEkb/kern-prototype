package com.siams.kern_prototype.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siams.kern_prototype.R
import com.siams.kern_prototype.domain.model.ServerSettings
import com.siams.kern_prototype.ui.theme.Body2Bold
import com.siams.kern_prototype.ui.theme.Body3
import com.siams.kern_prototype.ui.theme.Body3Bold
import com.siams.kern_prototype.ui.theme.Body4
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.theme.Green30
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.ui.theme.Red30
import com.siams.kern_prototype.viewmodel.SettingsViewModel
import com.siams.kern_prototype.viewmodel.TestState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: (() -> Unit)?,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()

    // Локальное редактируемое состояние формы; инициализируется из сохранённого.
    var host by remember(saved.host) { mutableStateOf(saved.host) }
    var port by remember(saved.port) { mutableStateOf(saved.port.toString()) }
    var https by remember(saved.useHttps) { mutableStateOf(saved.useHttps) }
    var login by remember(saved.login) { mutableStateOf(saved.login) }
    var password by remember(saved.password) { mutableStateOf(saved.password) }

    fun current() = ServerSettings(
        host = host.trim(),
        port = port.toIntOrNull() ?: 8080,
        useHttps = https,
        login = login.trim(),
        password = password,
        fieldsConfigLoadedAt = saved.fieldsConfigLoadedAt,
    )

    val portValid = port.toIntOrNull()?.let { it in 1..65535 } == true
    val formValid = host.isNotBlank() && portValid

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(KernTokens.spaceLg)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(KernTokens.spaceMd),
        ) {
            OutlinedTextField(
                value = host,
                onValueChange = { host = it },
                label = { Text(stringResource(R.string.settings_host_label)) },
                singleLine = true,
                isError = host.isBlank(),
                supportingText = { if (host.isBlank()) Text(stringResource(R.string.settings_required), color = Red30) },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.settings_port_label)) },
                singleLine = true,
                isError = !portValid,
                supportingText = { if (!portValid) Text(stringResource(R.string.settings_port_range), color = Red30) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = https, onCheckedChange = { https = it })
                Text(stringResource(R.string.settings_https), style = Body3)
            }
            OutlinedTextField(
                value = login,
                onValueChange = { login = it },
                label = { Text(stringResource(R.string.settings_login_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.settings_password_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(KernTokens.spaceMd)) {
                Button(
                    onClick = { vm.save(current()) },
                    modifier = Modifier.weight(1f),
                    enabled = formValid,
                ) { Text(stringResource(R.string.common_save)) }
                OutlinedButton(
                    onClick = { vm.testConnection(current()) },
                    modifier = Modifier.weight(1f),
                    enabled = test !is TestState.Testing && formValid,
                ) { Text(stringResource(R.string.settings_test)) }
            }

            when (val t = test) {
                TestState.Idle -> {}
                TestState.Testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = KernTokens.spaceSm))
                    Text(stringResource(R.string.settings_testing), style = Body3)
                }
                is TestState.Success -> Text(t.message, style = Body3, color = Green30)
                is TestState.Failure -> Text(t.message, style = Body3, color = Red30)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = KernTokens.spaceSm))

            // ---- Конфигурация паспортных полей (§4.1) ----
            Text(stringResource(R.string.settings_fields_config_title), style = Body2Bold)

            val loadedAt = saved.fieldsConfigLoadedAt
            val loaded = loadedAt > 0L
            OutlinedButton(
                onClick = { vm.loadFieldsConfig() },
                enabled = test !is TestState.Testing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.settings_load_config)) }

            val statusText = if (!loaded) {
                stringResource(R.string.settings_config_not_loaded)
            } else {
                stringResource(
                    R.string.settings_config_loaded,
                    vm.fields.size,
                    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(loadedAt)),
                )
            }
            Text(statusText, style = Body3, color = Gray40)

            Text(
                stringResource(R.string.settings_config_endpoint_note),
                style = Body4,
                color = Gray40,
            )

            if (loaded) {
                Text(stringResource(R.string.settings_current_fields), style = Body3Bold, modifier = Modifier.padding(top = KernTokens.spaceXs))
                vm.fields.forEach { f ->
                    Text(stringResource(R.string.settings_field_bullet, f.ruLabel), style = Body3, color = Gray40)
                }
            }
        }
    }
}
