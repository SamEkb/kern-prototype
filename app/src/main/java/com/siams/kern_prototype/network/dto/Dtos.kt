package com.siams.kern_prototype.network.dto

import kotlinx.serialization.Serializable

/**
 * DTO контракта s900 (§8.4). Имена полей точны. Прочие ключи AnalysisSource —
 * плоские (гео-атрибуты §8.5) — формируются как JsonObject в [com.siams.kern_prototype.sync].
 */

/** Ответ GET /analysis/source/{id} — нужен для поиска FieldOfView по id кадра (§8.3, шаг 3). */
@Serializable
data class AnalysisSourceResponse(
    val id: String? = null,
    val title: String? = null,
    val fieldOfViews: List<FieldOfViewDto> = emptyList(),
)

@Serializable
data class FieldOfViewDto(
    val id: String? = null,
    val idx: Int? = null,
    val sourceImages: List<SourceImageRefDto> = emptyList(),
)

@Serializable
data class SourceImageRefDto(
    val id: String? = null,
    val idx: Int? = null,
)

/** Тело PUT /analysis/source/{id}/image/{iid} — метаданные кадра (§8.3, шаги 4/6). */
@Serializable
data class SourceImageUpdateDto(
    val attributes: ImageAttributesDto,
)

@Serializable
data class ImageAttributesDto(
    val lampType: String,
    val stackUid: String,
    val coreImageType: String = "UNKNOWN",
    val kernUpperDepth: Double? = null,
    val kernLowerDepth: Double? = null,
    val kernDiameter: Double? = null,
)
