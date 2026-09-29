package com.siams.kern_prototype.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.siams.kern_prototype.data.db.entity.LayerEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LayerDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(layer: LayerEntity)

    @Update
    suspend fun update(layer: LayerEntity)

    @Query("DELETE FROM layer WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM layer WHERE id = :id")
    suspend fun getById(id: String): LayerEntity?

    @Query("SELECT * FROM layer WHERE tableId = :tableId AND lampType = :lampType LIMIT 1")
    suspend fun getByTableAndLamp(tableId: String, lampType: String): LayerEntity?

    @Query("SELECT * FROM layer WHERE tableId = :tableId")
    fun observeByTable(tableId: String): Flow<List<LayerEntity>>

    @Query("SELECT * FROM layer WHERE tableId = :tableId")
    suspend fun listByTable(tableId: String): List<LayerEntity>

    /** Сколько слоёв ссылаются на файл (фото или миниатюру) — для безопасной уборки (R08). */
    @Query("SELECT COUNT(*) FROM layer WHERE filePath = :path OR thumbPath = :path")
    suspend fun countReferencing(path: String): Int

    @Query("SELECT filePath FROM layer")
    suspend fun allFilePaths(): List<String>

    /**
     * Файл принят сервером; метаданные для него ещё не подтверждены (R01). R05: id пишется только
     * в ту версию слоя, файл которой был загружен ([filePath]); если слой успел переснять —
     * 0 строк, и ответ старого POST не подтверждает новый кадр.
     */
    @Query(
        """UPDATE layer SET remoteImageId = :remoteImageId, metaConfirmed = 0
           WHERE id = :id AND filePath = :filePath AND remoteImageId IS NULL""",
    )
    suspend fun setRemoteImageId(id: String, filePath: String, remoteImageId: String): Int

    /** R05: на сервере остался кадр прежней версии слоя — см. [LayerEntity.staleRemoteImageId]. */
    @Query("UPDATE layer SET staleRemoteImageId = :remoteImageId WHERE id = :id")
    suspend fun markStaleUpload(id: String, remoteImageId: String)

    /** Глубины стола — адресно (без перезаписи прочих полей), с отменой подтверждения метаданных. */
    @Query(
        """UPDATE layer SET kernUpperDepth = :upper, kernLowerDepth = :lower, metaConfirmed = 0
           WHERE tableId = :tableId""",
    )
    suspend fun setDepthByTable(tableId: String, upper: Double?, lower: Double?)

    /**
     * R01: отметить метаданные подтверждёнными, только если слой не изменился с момента
     * формирования отправленного PUT (иначе правка, сделанная во время отправки, потерялась бы).
     * @return число обновлённых строк (0 — слой успел измениться, PUT повторится при следующей отправке).
     */
    @Query(
        """UPDATE layer SET metaConfirmed = 1
           WHERE id = :id AND filePath = :filePath AND remoteImageId = :remoteImageId
             AND coreImageType = :coreImageType AND kernUpperDepth IS :upper AND kernLowerDepth IS :lower""",
    )
    suspend fun confirmMeta(
        id: String,
        filePath: String,
        remoteImageId: String,
        coreImageType: String,
        upper: Double?,
        lower: Double?,
    ): Int
}
