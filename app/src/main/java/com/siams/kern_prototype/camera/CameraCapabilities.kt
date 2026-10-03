package com.siams.kern_prototype.camera

/**
 * FIXES §3: возможности активной камеры для интерфейса — неподдерживаемые параметры не показываются.
 * Поле профиля при этом не удаляется (нужно запасному пути Camera2 и совместимости профилей).
 */
data class CameraCapabilities(
    /** Камера применяет [com.siams.kern_prototype.domain.model.CameraProfile.focusDistance]. */
    val supportsFocus: Boolean = false,
)
