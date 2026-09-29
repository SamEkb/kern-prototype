package com.siams.kern_prototype.domain.model

import kotlinx.serialization.Serializable

/**
 * Тип освещения слоя. Значения обязаны совпадать с сервером s900
 * (enum `LampType` в `data/.../analysis/source/LampType.java`) — сериализуются
 * в JSON ровно как "DAYLIGHT" / "UF". УФ = `UF` (не "UV"). См. §2 ТЗ.
 */
@Serializable
enum class LampType {
    DAYLIGHT,
    UF;

    val ruLabel: String
        get() = when (this) {
            DAYLIGHT -> "Дневной свет"
            UF -> "УФ"
        }
}
