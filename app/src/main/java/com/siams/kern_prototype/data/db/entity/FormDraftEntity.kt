package com.siams.kern_prototype.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * R15: несохранённый ввод формы (паспорт долбления, глубины стола) — переживает «Назад» и
 * перезапуск процесса. Хранится, пока ввод не записан в данные (неверный или ещё не созданное
 * долбление). [key] — `passport:<id>`, `passport:new`, `depth:<tableId>`; [valuesJson] — поля
 * формы как введены (JSON-объект строк).
 */
@Entity(tableName = "form_draft")
data class FormDraftEntity(
    @PrimaryKey val key: String,
    val valuesJson: String,
    val updatedAt: Long,
)
