package com.siams.kern_prototype.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.siams.kern_prototype.domain.model.LampType

/** Действия диалогов редактора профилей (в приложении — методы [com.siams.kern_prototype.viewmodel.CameraProfilesViewModel]). */
internal class ProfileEditorDialogActions(
    val confirmSave: () -> Unit,
    val dismissWarning: () -> Unit,
    val confirmFfc: () -> Unit,
    val dismissFfc: () -> Unit,
    val saveAllAndExit: () -> Unit,
    val discardAndExit: () -> Unit,
    val cancelExit: () -> Unit,
    val confirmImport: () -> Unit,
    val dismissImport: () -> Unit,
)

/** Кнопка диалога: подпись и действие. */
internal class DialogButton(val label: String, val onClick: () -> Unit)

/**
 * Модель диалога подтверждения: [confirm] — основная кнопка, [dismiss] — остальные (по порядку),
 * [onDismissRequest] — закрытие вне кнопок (назад, тап мимо).
 */
internal class DialogModel(
    val title: String,
    val text: String,
    val confirm: DialogButton,
    val dismiss: List<DialogButton>,
    val onDismissRequest: () -> Unit,
)

/**
 * Подтверждения редактора профилей по состоянию ViewModel (она только поднимает флаг и ждёт решения
 * оператора). Чистая функция — проверяется тестами без отрисовки.
 */
internal fun profileEditorDialogs(
    lamp: LampType,
    dirty: Set<LampType>,
    showWarning: Boolean,
    showFfcInstruction: Boolean,
    exitPrompt: Set<LampType>?,
    showImportWarning: Boolean,
    actions: ProfileEditorDialogActions,
): List<DialogModel> = buildList {
    // §4.1: предупреждение о несопоставимости снимков при изменении уже сохранённого профиля.
    if (showWarning) {
        add(
            DialogModel(
                title = "Изменить сохранённый профиль?",
                text = "Изменение настроек профиля «${lamp.ruLabel}» может привести к тому, что порода " +
                    "будет выглядеть иначе на новых снимках по сравнению с уже отснятыми материалами. " +
                    "Это может нарушить сопоставимость снимков внутри долбления.\n\n" +
                    "Сохранить изменения?",
                confirm = DialogButton("Сохранить", actions.confirmSave),
                dismiss = listOf(DialogButton("Отмена", actions.dismissWarning)),
                onDismissRequest = actions.dismissWarning,
            ),
        )
    }
    // §2.3: инструкция перед съёмкой эталона FFC.
    if (showFfcInstruction) {
        add(
            DialogModel(
                title = "Коррекция освещения (FFC)",
                text = "Поместите в фотокабину лист белой бумаги или иной равномерный белый фон так, " +
                    "чтобы он заполнял весь кадр. После нажатия «Снять эталон» будет сделан " +
                    "эталонный снимок для профиля «${lamp.ruLabel}» — по нему все последующие " +
                    "снимки керна будут выравниваться по освещению. Эталон проверяется: без " +
                    "пересвета, с достаточной яркостью и равномерным фоном; непригодный будет " +
                    "отклонён, а прежний эталон — сохранён.",
                confirm = DialogButton("Снять эталон", actions.confirmFfc),
                dismiss = listOf(DialogButton("Отмена", actions.dismissFfc)),
                onDismissRequest = actions.dismissFfc,
            ),
        )
    }
    // R23: несохранённые черновики при выходе.
    exitPrompt?.let { lamps ->
        add(
            DialogModel(
                title = "Сохранить изменения профилей?",
                text = "Не сохранены изменения профилей: " +
                    lamps.sortedBy { it.ordinal }.joinToString { "«${it.ruLabel}»" } + ". " +
                    "Сохранение изменит профили, по которым идёт съёмка: новые снимки могут " +
                    "отличаться от уже отснятых.",
                confirm = DialogButton("Сохранить всё", actions.saveAllAndExit),
                dismiss = listOf(DialogButton("Не сохранять", actions.discardAndExit), DialogButton("Остаться", actions.cancelExit)),
                onDismissRequest = actions.cancelExit,
            ),
        )
    }
    // §4.2: то же предупреждение о несопоставимости снимков перед импортом профилей.
    if (showImportWarning) {
        add(
            DialogModel(
                title = "Импортировать профили?",
                text = "Импорт заменит текущие профили камеры (дневной и УФ) параметрами из файла. " +
                    "Это может привести к тому, что порода будет выглядеть иначе на новых снимках " +
                    "по сравнению с уже отснятыми материалами, и нарушить их сопоставимость.\n\n" +
                    (if (dirty.isNotEmpty()) "Несохранённые изменения будут потеряны: " +
                        dirty.sortedBy { it.ordinal }.joinToString { it.ruLabel } + ".\n\n" else "") +
                    "Применить импортированные профили?",
                confirm = DialogButton("Импортировать", actions.confirmImport),
                dismiss = listOf(DialogButton("Отмена", actions.dismissImport)),
                onDismissRequest = actions.dismissImport,
            ),
        )
    }
}

/** Отрисовка подтверждений — вне ветвления компоновок (FIXES §8): в любой ориентации. */
@Composable
internal fun ProfileEditorDialogs(dialogs: List<DialogModel>) {
    dialogs.forEach { d ->
        AlertDialog(
            onDismissRequest = d.onDismissRequest,
            title = { Text(d.title) },
            text = { Text(d.text) },
            confirmButton = { TextButton(onClick = d.confirm.onClick) { Text(d.confirm.label) } },
            dismissButton = {
                Row { d.dismiss.forEach { b -> TextButton(onClick = b.onClick) { Text(b.label) } } }
            },
        )
    }
}
