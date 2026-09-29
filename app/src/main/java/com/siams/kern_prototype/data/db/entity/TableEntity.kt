package com.siams.kern_prototype.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Стол — лоток/короб с керном (§2, §7). Каскадно удаляется вместе с долблением. */
@Entity(
    tableName = "core_table",
    foreignKeys = [
        ForeignKey(
            entity = DrillingEntity::class,
            parentColumns = ["id"],
            childColumns = ["drillingId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("drillingId")],
)
data class TableEntity(
    @PrimaryKey val id: String,
    val drillingId: String,
    /** Номер стола внутри долбления: №1, №2 … */
    val number: Int,
    /** UUID, попадает в attributes.stackUid обоих слоёв (группировка в стек). */
    val stackUid: String,
)
