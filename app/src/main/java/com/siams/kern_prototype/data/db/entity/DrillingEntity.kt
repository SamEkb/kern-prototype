package com.siams.kern_prototype.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.siams.kern_prototype.domain.model.SyncStatus

/** Долбление (§2, §7). `id` (UUID) он же id AnalysisSource на сервере. */
@Entity(tableName = "drilling")
data class DrillingEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** Произвольные паспортные гео-поля как JSON-объект (§8.5). */
    val passportJson: String,
    val createdAt: Long,
    val syncStatus: SyncStatus = SyncStatus.NOT_SENT,
    /** id проекта на s900 (== id при создании; идемпотентно). */
    val remoteId: String? = null,
    val lastError: String? = null,
    /** Прогресс текущей отправки: сколько столов залито из [totalTables] (§9). */
    val uploadedTables: Int = 0,
    val totalTables: Int = 0,
)
