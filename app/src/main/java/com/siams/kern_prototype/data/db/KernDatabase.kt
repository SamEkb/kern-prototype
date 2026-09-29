package com.siams.kern_prototype.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.siams.kern_prototype.data.db.dao.DrillingDao
import com.siams.kern_prototype.data.db.dao.LayerDao
import com.siams.kern_prototype.data.db.dao.TableDao
import com.siams.kern_prototype.data.db.entity.DrillingEntity
import com.siams.kern_prototype.data.db.entity.LayerEntity
import com.siams.kern_prototype.data.db.entity.TableEntity

@Database(
    entities = [DrillingEntity::class, TableEntity::class, LayerEntity::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class KernDatabase : RoomDatabase() {
    abstract fun drillingDao(): DrillingDao
    abstract fun tableDao(): TableDao
    abstract fun layerDao(): LayerDao

    companion object {
        const val NAME = "kern.db"
    }
}
