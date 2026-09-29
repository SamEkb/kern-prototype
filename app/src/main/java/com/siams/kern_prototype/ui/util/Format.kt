package com.siams.kern_prototype.ui.util

import java.util.Locale

/** Человекочитаемый размер занятого места (§11 «индикация занятого места»). */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes Б"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.getDefault(), "%.0f КБ", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(Locale.getDefault(), "%.1f МБ", mb)
    val gb = mb / 1024.0
    return String.format(Locale.getDefault(), "%.2f ГБ", gb)
}
