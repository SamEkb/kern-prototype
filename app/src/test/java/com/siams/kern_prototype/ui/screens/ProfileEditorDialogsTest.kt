package com.siams.kern_prototype.ui.screens

import com.siams.kern_prototype.domain.model.LampType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FIXES §8 (5h): какие подтверждения редактора показаны и что делают их кнопки. */
class ProfileEditorDialogsTest {

    private val calls = mutableListOf<String>()
    private val actions = ProfileEditorDialogActions(
        confirmSave = { calls += "confirmSave" },
        dismissWarning = { calls += "dismissWarning" },
        confirmFfc = { calls += "confirmFfc" },
        dismissFfc = { calls += "dismissFfc" },
        saveAllAndExit = { calls += "saveAllAndExit" },
        discardAndExit = { calls += "discardAndExit" },
        cancelExit = { calls += "cancelExit" },
        confirmImport = { calls += "confirmImport" },
        dismissImport = { calls += "dismissImport" },
    )

    private fun dialogs(
        save: Boolean = false, ffc: Boolean = false, exit: Set<LampType>? = null, import: Boolean = false,
    ) = profileEditorDialogs(LampType.UF, setOf(LampType.DAYLIGHT), save, ffc, exit, import, actions)

    /** Нажать все кнопки и закрытие диалога; вернуть вызванные действия. */
    private fun pressAll(d: DialogModel): List<String> {
        calls.clear()
        d.confirm.onClick(); d.dismiss.forEach { it.onClick() }; d.onDismissRequest()
        return calls.toList()
    }

    @Test
    fun nothingShownWithoutFlags() = assertTrue(dialogs().isEmpty())

    @Test
    fun saveWarning() {
        val d = dialogs(save = true).single()
        assertEquals("Изменить сохранённый профиль?", d.title)
        assertTrue(d.text.contains("«${LampType.UF.ruLabel}»"))
        assertEquals("Сохранить", d.confirm.label)
        assertEquals(listOf("Отмена"), d.dismiss.map { it.label })
        assertEquals(listOf("confirmSave", "dismissWarning", "dismissWarning"), pressAll(d))
    }

    @Test
    fun ffcInstruction() {
        val d = dialogs(ffc = true).single()
        assertEquals("Коррекция освещения (FFC)", d.title)
        assertEquals("Снять эталон", d.confirm.label)
        assertEquals(listOf("confirmFfc", "dismissFfc", "dismissFfc"), pressAll(d))
    }

    @Test
    fun exitPromptHasThreeChoicesAndNamesProfiles() {
        val d = dialogs(exit = setOf(LampType.UF, LampType.DAYLIGHT)).single()
        assertEquals("Сохранить изменения профилей?", d.title)
        assertTrue(d.text, d.text.contains("«${LampType.DAYLIGHT.ruLabel}», «${LampType.UF.ruLabel}»"))
        assertEquals("Сохранить всё", d.confirm.label)
        assertEquals(listOf("Не сохранять", "Остаться"), d.dismiss.map { it.label })
        assertEquals("закрытие вне кнопок — «Остаться»", listOf("saveAllAndExit", "discardAndExit", "cancelExit", "cancelExit"), pressAll(d))
    }

    @Test
    fun importWarningMentionsLostDrafts() {
        val d = dialogs(import = true).single()
        assertEquals("Импортировать профили?", d.title)
        assertTrue(d.text.contains("Несохранённые изменения будут потеряны: ${LampType.DAYLIGHT.ruLabel}"))
        assertEquals(listOf("confirmImport", "dismissImport", "dismissImport"), pressAll(d))
    }

    @Test
    fun independentFlagsShowIndependentDialogs() {
        assertEquals(
            listOf("Изменить сохранённый профиль?", "Импортировать профили?"),
            dialogs(save = true, import = true).map { it.title },
        )
    }
}
