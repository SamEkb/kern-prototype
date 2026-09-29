package com.siams.kern_prototype.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.siams.kern_prototype.data.db.entity.TableEntity
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import kotlinx.coroutines.flow.Flow

@Dao
interface TableDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(table: TableEntity)

    @Update
    suspend fun update(table: TableEntity)

    @Query("DELETE FROM core_table WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM core_table WHERE id = :id")
    suspend fun getById(id: String): TableEntity?

    @Transaction
    @Query("SELECT * FROM core_table WHERE id = :id")
    fun observeWithLayers(id: String): Flow<TableWithLayers?>

    @Transaction
    @Query("SELECT * FROM core_table WHERE drillingId = :drillingId ORDER BY number ASC")
    fun observeByDrilling(drillingId: String): Flow<List<TableWithLayers>>

    @Query("SELECT COALESCE(MAX(number), 0) FROM core_table WHERE drillingId = :drillingId")
    suspend fun maxNumber(drillingId: String): Int

    @Query("SELECT COUNT(*) FROM core_table WHERE drillingId = :drillingId")
    suspend fun countByDrilling(drillingId: String): Int
}
