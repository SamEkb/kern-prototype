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

    @Query("UPDATE drilling SET remoteId = :remoteId WHERE id = :id")
    suspend fun setRemoteId(id: String, remoteId: String)

    @Query("UPDATE drilling SET uploadedTables = :uploaded, totalTables = :total WHERE id = :id")
    suspend fun setUploadProgress(id: String, uploaded: Int, total: Int)
}
