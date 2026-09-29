package com.siams.kern_prototype.data.db.entity

import androidx.room.ColumnInfo
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
    /**
     * R01/R05: счётчик локальных правок (паспорт, столы, слои, глубины). Отправка объявляется
     * успешной (SENT) только если ревизия не изменилась с момента её начала.
     */
    @ColumnInfo(defaultValue = "0") val editRevision: Long = 0,
    /**
     * R03: назначение ([com.siams.kern_prototype.domain.model.S900Destination.key]), в котором
     * действительны [remoteId] и remoteImageId слоёв. null при заданном remoteId — запись до v5.
     */
    val remoteDestination: String? = null,
)
