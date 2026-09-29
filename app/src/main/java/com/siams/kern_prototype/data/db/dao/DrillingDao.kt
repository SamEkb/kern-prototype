package com.siams.kern_prototype.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.siams.kern_prototype.data.db.entity.DrillingEntity
import com.siams.kern_prototype.data.db.relation.DrillingWithContent
import com.siams.kern_prototype.data.db.relation.DrillingWithTables
import com.siams.kern_prototype.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DrillingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(drilling: DrillingEntity)

    @Update
    suspend fun update(drilling: DrillingEntity)

    @Query("DELETE FROM drilling WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM drilling WHERE id = :id")
    suspend fun getById(id: String): DrillingEntity?

    @Query("SELECT * FROM drilling WHERE id = :id")
    fun observeById(id: String): Flow<DrillingEntity?>

    @Transaction
    @Query("SELECT * FROM drilling ORDER BY createdAt DESC")
    fun observeAllWithTables(): Flow<List<DrillingWithTables>>

    @Transaction
    @Query("SELECT * FROM drilling WHERE id = :id")
    suspend fun getWithContent(id: String): DrillingWithContent?

    @Query("UPDATE drilling SET syncStatus = :status, lastError = :error WHERE id = :id")
    suspend fun setSyncStatus(id: String, status: SyncStatus, error: String?)

    /**
     * Любая локальная правка: +1 к ревизии; SENT/ERROR → NOT_SENT (§9). Во время SENDING статус
     * не трогаем — незавершённость поймает [markSentIfUnchanged].
     */
    @Query(
        """UPDATE drilling SET editRevision = editRevision + 1,
           lastError = CASE WHEN syncStatus IN ('SENT', 'ERROR') THEN NULL ELSE lastError END,
           syncStatus = CASE WHEN syncStatus IN ('SENT', 'ERROR') THEN 'NOT_SENT' ELSE syncStatus END
           WHERE id = :id""",
    )
    suspend fun markEdited(id: String)

    /**
     * Паспорт — адресно: меняются только название/паспорт, ревизия и статус (одним запросом,
     * без чтения-копирования всей записи, которое затирало бы параллельные изменения).
     * [title] = null — название не меняется.
     */
    @Query(
        """UPDATE drilling SET title = COALESCE(:title, title), passportJson = :passportJson,
           editRevision = editRevision + 1,
           lastError = CASE WHEN syncStatus IN ('SENT', 'ERROR') THEN NULL ELSE lastError END,
           syncStatus = CASE WHEN syncStatus IN ('SENT', 'ERROR') THEN 'NOT_SENT' ELSE syncStatus END
           WHERE id = :id""",
    )
    suspend fun updatePassport(id: String, title: String?, passportJson: String)

    /**
     * R01/R05: SENT одним запросом и только если (а) с начала отправки не было правок и (б) все
     * слои доставлены: файл принят, метаданные подтверждены, нет прежнего кадра на сервере.
     * @return 1 — статус выставлен.
     */
    @Query(
        """UPDATE drilling SET syncStatus = 'SENT', lastError = NULL
           WHERE id = :id AND editRevision = :revision AND NOT EXISTS (
             SELECT 1 FROM layer WHERE tableId IN (SELECT id FROM core_table WHERE drillingId = :id)
             AND (remoteImageId IS NULL OR metaConfirmed = 0 OR staleRemoteImageId IS NOT NULL))""",
    )
    suspend fun markSentIfComplete(id: String, revision: Long): Int

    /** Слои долбления без подтверждённой доставки — для текста ошибки, когда SENT не выставлен. */
    @Query(
        """SELECT COUNT(*) FROM layer WHERE tableId IN (SELECT id FROM core_table WHERE drillingId = :id)
           AND (remoteImageId IS NULL OR metaConfirmed = 0 OR staleRemoteImageId IS NOT NULL)""",
    )
    suspend fun countUndeliveredLayers(id: String): Int

    @Query("UPDATE drilling SET remoteId = :remoteId WHERE id = :id")
    suspend fun setRemoteId(id: String, remoteId: String)

    @Query("UPDATE drilling SET uploadedTables = :uploaded, totalTables = :total WHERE id = :id")
    suspend fun setUploadProgress(id: String, uploaded: Int, total: Int)
}
