package com.siams.kern_prototype.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.siams.kern_prototype.data.db.entity.FormDraftEntity

@Dao
interface FormDraftDao {
    @Upsert
    suspend fun upsert(draft: FormDraftEntity)

    @Query("SELECT * FROM form_draft WHERE `key` = :key")
    suspend fun get(key: String): FormDraftEntity?

    @Query("DELETE FROM form_draft WHERE `key` = :key")
    suspend fun delete(key: String)

    /** Черновики удалённых долблений/столов (после каскадного удаления). */
    @Query(
        """DELETE FROM form_draft WHERE
             (`key` LIKE 'depth:%' AND substr(`key`, 7) NOT IN (SELECT id FROM core_table))
             OR (`key` LIKE 'passport:%' AND `key` != 'passport:new' AND substr(`key`, 10) NOT IN (SELECT id FROM drilling))""",
    )
    suspend fun deleteOrphans()
}
