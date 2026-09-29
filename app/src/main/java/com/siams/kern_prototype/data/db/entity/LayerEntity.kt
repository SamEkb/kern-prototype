package com.siams.kern_prototype.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.siams.kern_prototype.domain.model.CoreImageType
import com.siams.kern_prototype.domain.model.LampType

/** Слой (снимок) стола с типом освещения (§2, §7). */
@Entity(
    tableName = "layer",
    foreignKeys = [
        ForeignKey(
            entity = TableEntity::class,
            parentColumns = ["id"],
            childColumns = ["tableId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("tableId"), Index(value = ["tableId", "lampType"], unique = true)],
)
data class LayerEntity(
    @PrimaryKey val id: String,
    val tableId: String,
    val lampType: LampType,
    /** Путь во filesDir. */
    val filePath: String,
    /** Путь к уменьшенной миниатюре для быстрых превью (§11); null → используем оригинал. */
    val thumbPath: String? = null,
    /** Снимок применённого CameraProfile (JSON) для аудита идентичности (§6.5). */
    val cameraProfileJson: String,
    val capturedAt: Long,
    val coreImageType: CoreImageType = CoreImageType.UNKNOWN,
    /** Глубина керна по столу (§8.5); необязательно. */
    val kernUpperDepth: Double? = null,
    val kernLowerDepth: Double? = null,
    /** id SourceImage после заливки — защита от повторной отправки (§9, §13). */
    val remoteImageId: String? = null,
)
