package com.siams.kern_prototype.domain.model

import kotlinx.serialization.Serializable

/**
 * Тип кадра керна для раскладки в фотокниге/каротаже s900.
 * Совпадает с `CoreImageTypeDto` (§8.4). Сериализуется по имени.
 */
@Serializable
enum class CoreImageType {
    UNKNOWN,
    CIRCULAR,
    LONGITUDINAL_CUT,
    SURFACE
}
