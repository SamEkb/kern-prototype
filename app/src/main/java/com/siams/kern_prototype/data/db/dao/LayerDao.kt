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

    @Query("SELECT filePath FROM layer")
    suspend fun allFilePaths(): List<String>

    @Query("UPDATE layer SET remoteImageId = :remoteImageId WHERE id = :id")
    suspend fun setRemoteImageId(id: String, remoteImageId: String)
}
