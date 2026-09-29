package com.siams.kern_prototype.ui.nav

/**
 * Маршруты навигации (§5). Значения в фигурных скобках — аргументы пути.
 */
object Routes {
    const val SETTINGS = "settings"
    const val DRILLING_LIST = "drillings"
    const val CAMERA_PROFILES = "camera_profiles"
    const val SEND_QUEUE = "send_queue"

    // Форма долбления: создание (без id) и редактирование (с id).
    const val DRILLING_FORM = "drilling_form?drillingId={drillingId}"
    fun drillingForm(drillingId: String? = null) =
        if (drillingId == null) "drilling_form?drillingId=" else "drilling_form?drillingId=$drillingId"

    const val TABLES = "tables/{drillingId}"
    fun tables(drillingId: String) = "tables/$drillingId"

    const val TABLE_VIEW = "table/{tableId}"
    fun tableView(tableId: String) = "table/$tableId"

    // Съёмка: обязательный drillingId, опциональный tableId (досъёмка) и lampType.
    const val CAPTURE = "capture/{drillingId}?tableId={tableId}&lamp={lamp}"
    fun capture(drillingId: String, tableId: String? = null, lamp: String? = null): String {
        val t = tableId ?: ""
        val l = lamp ?: ""
        return "capture/$drillingId?tableId=$t&lamp=$l"
    }

    const val ARG_DRILLING_ID = "drillingId"
    const val ARG_TABLE_ID = "tableId"
    const val ARG_LAMP = "lamp"
}
