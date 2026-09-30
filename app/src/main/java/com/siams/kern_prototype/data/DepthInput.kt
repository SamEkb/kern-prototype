package com.siams.kern_prototype.data

import com.siams.kern_prototype.data.db.entity.LayerEntity
import com.siams.kern_prototype.domain.model.LampType

/**
 * R15: глубины стола (кровля/подошва интервала керна, м).
 *
 *  - Ввод — текст как введён; число — тем же правилом, что дробные поля паспорта
 *    ([PassportCodec.parseDecimal]); пусто — «не задано».
 *  - Глубина не отрицательна; если заданы обе — кровля строго выше подошвы (меньше по значению).
 *  - Глубина — общая для стола: у всех его слоёв одинаковая (см. [tableDepths]).
 */
object DepthInput {

    enum class Field { UPPER, LOWER }

    data class Result(val upper: Double?, val lower: Double?, val errors: Map<Field, String>) {
        val valid: Boolean get() = errors.isEmpty()
    }

    fun check(upperText: String, lowerText: String): Result {
        val errors = mutableMapOf<Field, String>()
        fun parse(text: String, field: Field): Double? {
            val raw = text.trim()
            if (raw.isEmpty()) return null
            val v = PassportCodec.parseDecimal(raw)
            when {
                v == null -> errors[field] = "Нужно число, например 1520.5 или 1520,5"
                v < 0 -> errors[field] = "Глубина не может быть отрицательной"
                else -> return v
            }
            return null
        }
        val upper = parse(upperText, Field.UPPER)
        val lower = parse(lowerText, Field.LOWER)
        if (upper != null && lower != null && upper >= lower) {
            errors[Field.LOWER] = "Подошва должна быть глубже кровли (больше ${PassportCodec.formatDecimal(upper)})"
        }
        return Result(upper, lower, errors)
    }

    /** Проверка уже разобранных значений (для репозитория). null — корректно. */
    fun problem(upper: Double?, lower: Double?): String? = when {
        upper != null && (!upper.isFinite() || upper < 0) -> "неверная глубина кровли"
        lower != null && (!lower.isFinite() || lower < 0) -> "неверная глубина подошвы"
        upper != null && lower != null && upper >= lower -> "подошва должна быть глубже кровли"
        else -> null
    }

    /**
     * Глубины стола по его слоям: для каждой границы — значение DAYLIGHT, иначе UF (как показывает
     * редактор). Так же получает глубины новый/переснятый слой.
     */
    fun tableDepths(layers: List<LayerEntity>): Pair<Double?, Double?> {
        val ordered = layers.sortedBy { if (it.lampType == LampType.DAYLIGHT) 0 else 1 }
        return ordered.firstNotNullOfOrNull { it.kernUpperDepth } to ordered.firstNotNullOfOrNull { it.kernLowerDepth }
    }

    /** Слои стола расходятся по глубинам (данные до исправления R15). */
    fun mismatch(layers: List<LayerEntity>): Boolean =
        layers.map { it.kernUpperDepth to it.kernLowerDepth }.distinct().size > 1
}
