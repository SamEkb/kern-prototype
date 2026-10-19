package com.siams.kern_prototype.ui.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.siams.kern_prototype.camera.FfcQuality
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
    val retryFfc: () -> Unit,
    val dismissFfcRejection: () -> Unit,
    val confirmFfcRemove: () -> Unit,
    val dismissFfcRemove: () -> Unit,
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
    ffcRejection: FfcQuality.Report?,
    showFfcRemove: Boolean,
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
    // R22: эталон отклонён проверкой — причины целиком (в коротком сообщении они обрезались).
    ffcRejection?.let { report ->
        add(
            DialogModel(
                title = "Эталон FFC не принят",
                text = "Прежний эталон профиля «${report.lamp.ruLabel}» не изменён.\n\n" +
                    "Причины:\n" + report.problems.joinToString("\n") { "• $it" } + "\n\n" +
                    "Эталон снимают по равномерному белому фону, заполняющему весь кадр, — без керна " +
                    "и блестящих предметов (фольга, металл). Яркость подберите выдержкой или усилением " +
                    "либо кнопкой «Автоэкспозиция», когда в кадре белый фон." +
                    if (report.thresholds.provisional) "\n\nПороги проверки предварительные." else "",
                confirm = DialogButton("Снять заново", actions.retryFfc),
                dismiss = listOf(DialogButton("Закрыть", actions.dismissFfcRejection)),
                onDismissRequest = actions.dismissFfcRejection,
            ),
        )
    }
    // Удаление эталона FFC: только из профиля; файл остаётся для уже снятых слоёв (R11).
    if (showFfcRemove) {
        add(
            DialogModel(
                title = "Удалить эталон FFC?",
                text = "Профиль «${lamp.ruLabel}» перестанет выравнивать снимки по освещению. " +
                    "Изменение вступит в силу после сохранения профиля. На уже снятые снимки это не влияет.",
                confirm = DialogButton("Удалить", actions.confirmFfcRemove),
                dismiss = listOf(DialogButton("Отмена", actions.dismissFfcRemove)),
                onDismissRequest = actions.dismissFfcRemove,
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
            // Длинные причины (отказ FFC, импорт) в альбомной ориентации и при крупном шрифте:
            // AlertDialog сам не прокручивает текст.
            text = { Text(d.text, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = d.confirm.onClick) { Text(d.confirm.label) } },
            dismissButton = {
                Row { d.dismiss.forEach { b -> TextButton(onClick = b.onClick) { Text(b.label) } } }
            },
        )
    }
}
