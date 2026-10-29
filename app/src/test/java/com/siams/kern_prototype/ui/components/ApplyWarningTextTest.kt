package com.siams.kern_prototype.ui.components

import com.siams.kern_prototype.camera.ApplyWarning
import com.siams.kern_prototype.camera.WbOpenMethod
import com.siams.kern_prototype.domain.model.LampType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** Запрос RGB Gain — без предупреждения, но и без утверждения, что WB работает; резерв — явно. */
    @Test
    fun wbOpenWarningOnlyForFallbackAndUnknown() {
        assertNull(wbOpenWarningText(null))
        assertNull(wbOpenWarningText(WbOpenMethod.RGB_SUFFIX))
        val fallback = wbOpenWarningText(WbOpenMethod.FALLBACK_NO_SUFFIX)!!
        assertTrue(fallback, fallback.contains("резервном режиме") && fallback.contains("RGB Gain не выбран"))
        assertTrue(fallback, fallback.contains("автобаланс не подтверждены"))
        assertTrue(wbOpenWarningText(WbOpenMethod.UNKNOWN)!!.contains("не подтверждены"))
    }
}
