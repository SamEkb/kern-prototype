package com.siams.kern_prototype.ui.screens

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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val initial by vm.initial.collectAsStateWithLifecycle()
    val schema by vm.schema.collectAsStateWithLifecycle()
    val simplified = schema.isEmpty()

    var title by remember(initial.loaded) { mutableStateOf(initial.title) }
    val values = remember(initial.loaded) {
        mutableStateMapOf<String, String>().apply { putAll(initial.values) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (vm.isEditing) R.string.drilling_form_title_edit else R.string.drilling_new)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.drilling_form_name_label)) },
                placeholder = { Text(stringResource(R.string.drilling_form_name_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (simplified) {
                Text(stringResource(R.string.drilling_form_simplified), style = Body3, color = Gray40)
            } else {
                Text(stringResource(R.string.drilling_form_full), style = Body3, color = Gray40)
            }

            schema.forEach { field ->
                val isNumber = field.type != PassportFieldType.STRING
                OutlinedTextField(
                    value = values[field.key].orEmpty(),
                    onValueChange = { input ->
                        values[field.key] = if (isNumber) input.filter { it.isDigit() || it == '.' || it == ',' || it == '-' } else input
                    },
                    label = { Text(field.ruLabel) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (isNumber) KeyboardType.Number else KeyboardType.Text,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = { vm.save(title, values.toMap()) { id -> if (!vm.isEditing) onSavedNew(id) else onBack() } },
                enabled = title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = KernTokens.spaceMd),
            ) {
                Text(stringResource(if (vm.isEditing) R.string.common_save else R.string.drilling_form_create))
            }
        }
    }
}
