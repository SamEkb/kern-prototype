package com.siams.kern_prototype.domain.model

/**
 * Тип паспортного поля — определяет валидацию и способ сериализации в
 * `attributes` AnalysisSource (плоские ключи, §8.5).
 */
enum class PassportFieldType { STRING, INTEGER, DOUBLE }

/**
 * Описание одного паспортного поля долбления (гео-атрибут AnalysisSource).
 * `key` — точный JSON-ключ верхнего уровня (@JsonAnySetter на сервере, §8.4).
 */
data class PassportField(
    val key: String,
    val ruLabel: String,
    val type: PassportFieldType,
    val required: Boolean = false,
)

/**
 * Встроенная гео-схема паспортных полей (§8.5). Работает как дефолт без сервера
 * (REST-эндпоинта конфигурации ещё нет — §10.1). `title` в модели — это
 * [Drilling.title], здесь только гео-атрибуты.
 */
object PassportSchema {

    const val KEY_PERFORATION_NUMBER = "perforation_number"

    val default: List<PassportField> = listOf(
        PassportField("field", "Месторождение/площадь", PassportFieldType.STRING),
        PassportField("well_number", "Номер скважины", PassportFieldType.STRING),
        PassportField("wellbore", "Ствол", PassportFieldType.STRING),
        PassportField(KEY_PERFORATION_NUMBER, "Номер долбления", PassportFieldType.INTEGER),
        PassportField("core_recovery", "Вынос керна, м.", PassportFieldType.DOUBLE),
        PassportField("depth_correction", "Поправка, м.", PassportFieldType.DOUBLE),
        PassportField(
            "initial_sampling_reference_top",
            "Исходная привязка отбора, кровля, м.",
            PassportFieldType.DOUBLE,
        ),
        PassportField(
            "initial_sampling_reference_bottom",
            "Исходная привязка отбора, подошва, м.",
            PassportFieldType.DOUBLE,
        ),
    )
}
