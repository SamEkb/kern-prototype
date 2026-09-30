package com.siams.kern_prototype.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** R15: поля паспорта не теряют значения молча; строковые идентификаторы не нормализуются. */
class PassportCodecTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val codec = PassportCodec(json)

    private fun obj(s: String) = json.decodeFromString(JsonObject.serializer(), s)

    @Test
    fun stringIdentifiersKeepLeadingZeros() {
        val encoded = codec.encode(mapOf("well_number" to "0012", "field" to "Самотлор"))
        assertEquals(JsonPrimitive("0012"), obj(encoded)["well_number"])
        assertEquals("0012", codec.decodeToStrings(encoded)["well_number"])
        // Ранее сохранённая строка «0012» тоже показывается как есть (раньше становилась 12).
        assertEquals("0012", codec.decodeToStrings("""{"well_number":"0012"}""")["well_number"])
    }

    @Test
    fun invalidNumbersAreFieldErrorsNotSilentDrops() {
        val errors = codec.validate(
            mapOf(
                "perforation_number" to "1,5",
                "core_recovery" to "1..2",
                "depth_correction" to "-",
                "initial_sampling_reference_top" to "12а",
                "well_number" to "12а",       // строковое поле — любое значение допустимо
            ),
        )
        assertEquals(setOf("perforation_number", "core_recovery", "depth_correction", "initial_sampling_reference_top"), errors.keys)
        try {
            codec.encode(mapOf("perforation_number" to "12а"))
            fail("неверное число не должно молча пропадать")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!, e.message!!.contains("Номер долбления"))
        }
    }

    @Test
    fun validNumbersKeepTheirServerFormat() {
        val encoded = obj(codec.encode(mapOf(
            "perforation_number" to " 12 ",
            "core_recovery" to "1,5",
            "depth_correction" to "-0.25",
            "initial_sampling_reference_top" to "1500",
        )))
        assertEquals(JsonPrimitive(12), encoded["perforation_number"])
        assertEquals(JsonPrimitive(1.5), encoded["core_recovery"])
        assertEquals(JsonPrimitive(-0.25), encoded["depth_correction"])
        assertEquals(JsonPrimitive(1500.0), encoded["initial_sampling_reference_top"])
        assertTrue(codec.validate(mapOf("core_recovery" to "", "perforation_number" to "  ")).isEmpty())
    }

    @Test
    fun storedNumbersAreShownAsPlainDecimals() {
        val shown = codec.decodeToStrings("""{"perforation_number":12,"core_recovery":1.5,"depth_correction":1500.0}""")
        assertEquals(mapOf("perforation_number" to "12", "core_recovery" to "1.5", "depth_correction" to "1500"), shown)
        // Показанное значение снова проходит проверку и кодируется тем же числом.
        assertEquals(JsonPrimitive(1500.0), obj(codec.encode(shown))["depth_correction"])
    }

    /** encode → decode → validate → encode: малые и большие числа не становятся «неверными». */
    @Test
    fun roundTripKeepsSmallAndLargeNumbersValid() {
        for (v in listOf("0.0001", "0,0000001", "123456789012.5", "100000000000000000000", "-0.25", "0", "1500")) {
            val first = codec.encode(mapOf("core_recovery" to v))
            val shown = codec.decodeToStrings(first)
            assertTrue("$v → ${shown["core_recovery"]}", codec.validate(shown).isEmpty())
            assertEquals("$v: то же число после повторного сохранения", obj(first), obj(codec.encode(shown)))
        }
    }

    /** Старые записи с экспонентой (как kotlinx пишет Double) открываются без ошибок. */
    @Test
    fun legacyExponentRecordsAreShownValid() {
        val shown = codec.decodeToStrings("""{"core_recovery":1.0E-4,"depth_correction":1E+3,"initial_sampling_reference_top":2.5e-7,"well_number":"1E5"}""")
        assertEquals("0.0001", shown["core_recovery"])
        assertEquals("1000", shown["depth_correction"])
        assertEquals("0.00000025", shown["initial_sampling_reference_top"])
        assertEquals("строка — буквально", "1E5", shown["well_number"])
        assertTrue(codec.validate(shown).isEmpty())
        assertEquals(JsonPrimitive(1.0E-4), obj(codec.encode(shown))["core_recovery"])
    }
}
