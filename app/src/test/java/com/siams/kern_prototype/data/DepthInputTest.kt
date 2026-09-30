package com.siams.kern_prototype.data

import com.siams.kern_prototype.data.DepthInput.Field
import com.siams.kern_prototype.data.db.entity.LayerEntity
import com.siams.kern_prototype.domain.model.LampType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R15: правила глубин стола. */
class DepthInputTest {

    @Test
    fun validDepthsAreParsed() {
        val r = DepthInput.check(" 1520,5 ", "1530")
        assertTrue(r.valid)
        assertEquals(1520.5, r.upper!!, 0.0)
        assertEquals(1530.0, r.lower!!, 0.0)
        assertTrue("пусто — не задано", DepthInput.check("", "  ").let { it.valid && it.upper == null && it.lower == null })
        assertTrue("одна граница допустима", DepthInput.check("1520", "").valid)
    }

    @Test
    fun invalidInputIsAnErrorNotNull() {
        val r = DepthInput.check("12а", "1..2")
        assertEquals(setOf(Field.UPPER, Field.LOWER), r.errors.keys)
        assertEquals("Глубина не может быть отрицательной", DepthInput.check("-1", "").errors[Field.UPPER])
    }

    @Test
    fun upperMustBeAboveLower() {
        assertEquals(setOf(Field.LOWER), DepthInput.check("1530", "1520").errors.keys)
        assertEquals("нулевой интервал", setOf(Field.LOWER), DepthInput.check("1520", "1520").errors.keys)
        assertEquals("подошва должна быть глубже кровли", DepthInput.problem(10.0, 10.0))
        assertNull(DepthInput.problem(10.0, null))
        assertEquals("неверная глубина кровли", DepthInput.problem(-1.0, null))
    }

    private fun layer(lamp: LampType, upper: Double?, lower: Double?) = LayerEntity(
        id = lamp.name, tableId = "t", lampType = lamp, filePath = "/f", cameraProfileJson = "{}", capturedAt = 0,
        kernUpperDepth = upper, kernLowerDepth = lower,
    )

    @Test
    fun tableDepthsPreferDaylightPerBoundary() {
        val layers = listOf(layer(LampType.UF, 1.0, 9.0), layer(LampType.DAYLIGHT, 2.0, null))
        assertEquals(2.0 to 9.0, DepthInput.tableDepths(layers))
        assertTrue(DepthInput.mismatch(layers))
        assertFalse(DepthInput.mismatch(listOf(layer(LampType.UF, 1.0, 2.0), layer(LampType.DAYLIGHT, 1.0, 2.0))))
        assertEquals(null to null, DepthInput.tableDepths(emptyList()))
    }
}
