package com.siams.kern_prototype.data.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.siams.kern_prototype.domain.model.LampType
import java.io.File
import java.util.UUID

/**
 * R11: файлы эталонов FFC неизменяемы. Каждая съёмка/импорт эталона создаёт НОВЫЙ файл
 * с уникальным именем; файл, на который ссылается сохранённый профиль (и JSON профиля в
 * уже снятых слоях), никогда не перезаписывается. Старые эталоны автоматически не удаляются.
 */
object FfcFiles {
    fun newFile(context: Context, lamp: LampType): File =
        File(
            File(context.filesDir, "ffc"),
            "ffc_${lamp.name}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}.png",
        )

    /**
     * Загрузить эталон для коррекции. null — файл отсутствует, не читается или повреждён
     * (полная проверка [PngCheck], как при импорте: «частичный» эталон недопустим).
     */
    fun decodeTemplate(path: String): Bitmap? {
        val bytes = runCatching { File(path).readBytes() }.getOrNull() ?: return null
        return decodeTemplateBytes(bytes)
    }

    /** То же для байтов PNG (R22: проверка импортируемого эталона тем же загрузчиком, что и коррекция). */
    fun decodeTemplateBytes(bytes: ByteArray): Bitmap? {
        if (PngCheck.problem(bytes) != null) return null
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?.takeIf { it.config == Bitmap.Config.ARGB_8888 && it.width > 0 && it.height > 0 }
    }
}
