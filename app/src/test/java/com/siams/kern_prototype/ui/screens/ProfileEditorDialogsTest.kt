package com.siams.kern_prototype.ui.screens

import com.siams.kern_prototype.camera.FfcQuality
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
        retryFfc = { calls += "retryFfc" },
        dismissFfcRejection = { calls += "dismissFfcRejection" },
        confirmFfcRemove = { calls += "confirmFfcRemove" },
        dismissFfcRemove = { calls += "dismissFfcRemove" },
    )

    private fun dialogs(
        save: Boolean = false, ffc: Boolean = false, exit: Set<LampType>? = null, import: Boolean = false,
        rejection: FfcQuality.Report? = null, remove: Boolean = false,
    ) = profileEditorDialogs(LampType.UF, setOf(LampType.DAYLIGHT), save, ffc, exit, import, rejection, remove, actions)

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

    /** Причины отказа FFC — целиком, по пунктам (в коротком сообщении обрезались). */
    @Test
    fun ffcRejectionListsAllProblems() {
        val problems = listOf(
            "пересвет 2,73% пикселей (допустимо ≤ 0,10%) — уменьшите выдержку/усиление",
            "неравномерность: усиление коррекции R=5,00 (допустимо ≤ 4,00) — фон должен заполнять кадр",
        )
        val report = FfcQuality.Report(
            lamp = LampType.DAYLIGHT, width = 8, height = 6,
            channels = List(3) { FfcQuality.Channel(128.0, 120, 128, 136, 0.0) },
            anySaturatedFraction = 0.0273, correctionGain = listOf(5.0, 1.0, 1.0),
            thresholds = FfcQuality.Thresholds.forLamp(LampType.DAYLIGHT), problems = problems,
        )
        val d = dialogs(rejection = report).single()
        assertEquals("Эталон FFC не принят", d.title)
        problems.forEach { assertTrue(d.text, d.text.contains("• $it")) }
        assertTrue(d.text, d.text.contains("«${LampType.DAYLIGHT.ruLabel}» не изменён"))
        assertTrue(d.text, d.text.contains("предварительные"))
        assertEquals("Снять заново", d.confirm.label)
        assertEquals(listOf("retryFfc", "dismissFfcRejection", "dismissFfcRejection"), pressAll(d))
    }

    @Test
    fun ffcRemoveConfirmation() {
        val d = dialogs(remove = true).single()
        assertEquals("Удалить эталон FFC?", d.title)
        assertTrue(d.text, d.text.contains("«${LampType.UF.ruLabel}»") && d.text.contains("после сохранения"))
        assertEquals("Удалить", d.confirm.label)
        assertEquals(listOf("confirmFfcRemove", "dismissFfcRemove", "dismissFfcRemove"), pressAll(d))
    }

    @Test
    fun independentFlagsShowIndependentDialogs() {
        assertEquals(
            listOf("Изменить сохранённый профиль?", "Импортировать профили?"),
            dialogs(save = true, import = true).map { it.title },
        )
    }
}
