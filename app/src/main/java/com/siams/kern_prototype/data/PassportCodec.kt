package com.siams.kern_prototype.data

import com.siams.kern_prototype.domain.model.PassportFieldType
import com.siams.kern_prototype.domain.model.PassportSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кодирование паспортных гео-полей (§8.5) между вводом формы (строки),
 * хранением в Room (типизированный JSON) и телом AnalysisSource (плоские ключи).
 */
@Singleton
class PassportCodec @Inject constructor(private val json: Json) {

    /**
     * R15: ошибки ввода по ключам полей (пусто — всё корректно). Неверное число — ошибка поля,
     * а не молчаливый пропуск при сохранении. Типы полей — по [PassportSchema] (их смена, например
     * строковый «Номер долбления», ждёт согласования с S900 — Q04).
     */
    fun validate(values: Map<String, String>): Map<String, String> = buildMap {
        for (field in PassportSchema.default) {
            val raw = values[field.key]?.trim().orEmpty()
            if (raw.isEmpty()) continue
            when (field.type) {
                PassportFieldType.STRING -> Unit
                PassportFieldType.INTEGER -> if (parseInt(raw) == null) put(field.key, "Нужно целое число, например 12")
                PassportFieldType.DOUBLE -> if (parseDouble(raw) == null) put(field.key, "Нужно число, например 1.5 или 1,5")
            }
        }
    }

    /**
     * Ввод формы (строки по ключам) → типизированный JSON-объект для Room/сервера.
     * R15: неверное число — [IllegalArgumentException] (ничего не пропускается молча);
     * вызывающий сначала проверяет ввод через [validate]. Формат корректных значений не изменился.
     */
    fun encode(values: Map<String, String>): String {
        val errors = validate(values)
        require(errors.isEmpty()) {
            "Паспорт: " + errors.entries.joinToString("; ") { (k, e) -> "${PassportSchema.default.first { it.key == k }.ruLabel} — $e" }
        }
        val obj = buildJsonObject {
            for (field in PassportSchema.default) {
                val raw = values[field.key]?.trim().orEmpty()
                if (raw.isEmpty()) continue
                when (field.type) {
                    PassportFieldType.STRING -> put(field.key, JsonPrimitive(raw))
                    PassportFieldType.INTEGER -> put(field.key, JsonPrimitive(parseInt(raw)!!))
                    PassportFieldType.DOUBLE -> put(field.key, JsonPrimitive(parseDouble(raw)!!))
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /**
     * JSON из Room → значения-строки для повторного показа в форме. R15:
     *  - строковые значения — буквально («0012» остаётся «0012»);
     *  - числа JSON — в десятичной записи без экспоненты (1.0E-4 → «0.0001»), которую принимает
     *    [validate]: сохранённое значение без правки снова проходит проверку и кодируется тем же
     *    числом (в т.ч. старые записи с экспонентой).
     */
    fun decodeToStrings(passportJson: String): Map<String, String> {
        val obj = runCatching {
            json.decodeFromString(JsonObject.serializer(), passportJson)
        }.getOrNull() ?: return emptyMap()
        return obj.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { k to display(it) } }.toMap()
    }

    private fun display(p: JsonPrimitive): String {
        if (p.isString) return p.content
        return runCatching { java.math.BigDecimal(p.content).stripTrailingZeros().toPlainString() }
            .getOrDefault(p.content) // не число (true/false/null) — как записано
    }

    private fun parseInt(raw: String): Int? = raw.takeIf { INT.matches(it) }?.toIntOrNull()

    private fun parseDouble(raw: String): Double? =
        raw.takeIf { DOUBLE.matches(it) }?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

    fun toJsonObject(passportJson: String): JsonObject =
        runCatching { json.decodeFromString(JsonObject.serializer(), passportJson) }
            .getOrDefault(JsonObject(emptyMap()))

    private companion object {
        val INT = Regex("-?\\d+")
        val DOUBLE = Regex("-?\\d+([.,]\\d+)?")
    }
}
