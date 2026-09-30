package com.siams.kern_prototype.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import com.siams.kern_prototype.ui.theme.Orange30
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.siams.kern_prototype.R
import com.siams.kern_prototype.domain.model.PassportFieldType
import com.siams.kern_prototype.ui.theme.Body3
import com.siams.kern_prototype.ui.theme.Gray40
import com.siams.kern_prototype.ui.theme.KernTokens
import com.siams.kern_prototype.viewmodel.DrillingFormViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrillingFormScreen(
    onBack: () -> Unit,
    onSavedNew: (String) -> Unit,
    vm: DrillingFormViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val schema by vm.schema.collectAsStateWithLifecycle()
    val errors by vm.errors.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val restoredDraft by vm.restoredDraft.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    // R15: пока идёт выход/создание, ввод заблокирован (изменение не должно потеряться).
    val busy by vm.busy.collectAsStateWithLifecycle()
    val editable = loaded && !busy
    val simplified = schema.isEmpty()
    val values = form.values

    // R15: выход с экрана (кнопка и системный «Назад») дожидается автосохранения.
    val leave = { vm.flush(onBack) }
    BackHandler(onBack = leave)
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (vm.isEditing) R.string.drilling_form_title_edit else R.string.drilling_new)) },
                navigationIcon = {
                    IconButton(onClick = leave) {
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
            if (restoredDraft) {
                Text(stringResource(R.string.drilling_form_restored), style = Body3, color = Orange30)
                if (vm.isEditing) {
                    TextButton(onClick = vm::discardDraft, enabled = editable) { Text(stringResource(R.string.drilling_form_discard)) }
                }
            }

            val titleError = errors[DrillingFormViewModel.TITLE_KEY]
            OutlinedTextField(
                value = form.title,
                onValueChange = vm::onTitleChange,
                label = { Text(stringResource(R.string.drilling_form_name_label)) },
                placeholder = { Text(stringResource(R.string.drilling_form_name_placeholder)) },
                isError = titleError != null,
                supportingText = titleError?.let { { Text(it) } },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            )

            if (simplified) {
                Text(stringResource(R.string.drilling_form_simplified), style = Body3, color = Gray40)
            } else {
                Text(stringResource(R.string.drilling_form_full), style = Body3, color = Gray40)
            }

            schema.forEach { field ->
                val isNumber = field.type != PassportFieldType.STRING
                val error = errors[field.key]
                OutlinedTextField(
                    value = values[field.key].orEmpty(),
                    // R15: текст не фильтруется — неверный ввод остаётся видимым и получает ошибку.
                    onValueChange = { input -> vm.onFieldChange(field.key, input) },
                    enabled = editable,
                    label = { Text(field.ruLabel) },
                    // R15: неверное значение не пропадает молча — ошибка у поля.
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // R15: правка сохраняется автоматически; статус — под формой.
            if (vm.isEditing) {
                Text(
                    stringResource(
                        when (status) {
                            DrillingFormViewModel.SaveStatus.SAVED -> R.string.drilling_form_status_saved
                            DrillingFormViewModel.SaveStatus.INVALID -> R.string.drilling_form_status_invalid
                            DrillingFormViewModel.SaveStatus.DRAFT -> R.string.drilling_form_status_draft
                        },
                    ),
                    style = Body3,
                    color = if (status == DrillingFormViewModel.SaveStatus.INVALID) Orange30 else Gray40,
                )
            }
            Button(
                onClick = { if (vm.isEditing) leave() else vm.create(onSavedNew) },
                enabled = editable && (vm.isEditing || form.title.isNotBlank()),
                modifier = Modifier.fillMaxWidth().padding(top = KernTokens.spaceMd),
            ) {
                Text(stringResource(if (vm.isEditing) R.string.drilling_form_done else R.string.drilling_form_create))
            }
        }
    }
}
