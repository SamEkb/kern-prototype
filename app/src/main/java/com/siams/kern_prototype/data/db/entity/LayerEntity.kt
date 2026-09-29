package com.siams.kern_prototype.data.db.entity

import androidx.room.ColumnInfo
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
    /**
     * R01: метаданные (lampType/stackUid/coreImageType/глубины) подтверждены сервером (PUT)
     * для текущего [remoteImageId]. Сбрасывается при новой загрузке файла и правке метаданных;
     * при повторной отправке недоставленный PUT выполняется, а файл повторно не грузится.
     */
    @ColumnInfo(defaultValue = "0") val metaConfirmed: Boolean = false,
    /**
     * R05: id изображения, загруженного из ПРЕЖНЕЙ версии файла слоя (слой переснят, пока шёл
     * POST). Этот кадр остался на сервере; автоматически замена не считается выполненной —
     * пока поле задано, долбление не может получить SENT (нужен контракт замены на S900, R04).
     */
    val staleRemoteImageId: String? = null,
    /**
     * R02: SHA-256 файла снимка (hex), записанный при фиксации кадра. Перед отправкой файл
     * сверяется с ним — ловится любое повреждение, в том числе в середине файла. null — снимок
     * до v5 (проверяется полным декодированием).
     */
    val fileSha256: String? = null,
)
