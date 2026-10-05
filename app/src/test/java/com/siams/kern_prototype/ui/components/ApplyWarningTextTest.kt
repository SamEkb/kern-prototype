package com.siams.kern_prototype.ui.components

import com.siams.kern_prototype.camera.ApplyWarning
import com.siams.kern_prototype.domain.model.LampType
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplyWarningTextTest {
    @Test
    fun namesLampParametersAndCodesWithoutBlocking() {
        val text = applyWarningText(ApplyWarning(LampType.UF, linkedMapOf("WhiteBalanceGain" to "0x80004001", "Gamma" to "0x80070057")))
        assertEquals(
            "Камера не приняла параметры профиля «${LampType.UF.ruLabel}»: баланс белого (0x80004001), гамма (0x80070057). " +
                "Снимки могут не соответствовать профилю; съёмка не заблокирована.",
            text,
        )
    }
}
