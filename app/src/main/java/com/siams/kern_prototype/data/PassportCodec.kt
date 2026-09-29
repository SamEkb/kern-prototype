package com.siams.kern_prototype.data

import com.siams.kern_prototype.domain.model.PassportFieldType
import com.siams.kern_prototype.domain.model.PassportSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Кодирование паспортных гео-полей (§8.5) между вводом формы (строки),
 * хранением в Room (типизированный JSON) и телом AnalysisSource (плоские ключи).
 */
@Singleton
class PassportCodec @Inject constructor(private val json: Json) {

    /** Ввод формы (строки по ключам) → типизированный JSON-объект для Room/сервера. */
    fun encode(values: Map<String, String>): String {
        val obj = buildJsonObject {
            for (field in PassportSchema.default) {
                val raw = values[field.key]?.trim().orEmpty()
                if (raw.isEmpty()) continue
                when (field.type) {
                    PassportFieldType.STRING -> put(field.key, JsonPrimitive(raw))
                    PassportFieldType.INTEGER -> raw.toIntOrNull()?.let { put(field.key, JsonPrimitive(it)) }
                    PassportFieldType.DOUBLE -> raw.replace(',', '.').toDoubleOrNull()
                        ?.let { put(field.key, JsonPrimitive(it)) }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    /** JSON из Room → значения-строки для повторного показа в форме. */
    fun decodeToStrings(passportJson: String): Map<String, String> {
        val obj = runCatching {
            json.decodeFromString(JsonObject.serializer(), passportJson)
        }.getOrNull() ?: return emptyMap()
        return obj.mapValues { (_, v) ->
            val prim = v.jsonPrimitive
            prim.intOrNull?.toString()
                ?: prim.doubleOrNull?.toString()
                ?: prim.content
        }
    }

    fun toJsonObject(passportJson: String): JsonObject =
        runCatching { json.decodeFromString(JsonObject.serializer(), passportJson) }
            .getOrDefault(JsonObject(emptyMap()))
}
