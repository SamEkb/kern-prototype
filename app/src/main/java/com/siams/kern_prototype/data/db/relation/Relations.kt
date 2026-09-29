package com.siams.kern_prototype.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.siams.kern_prototype.data.db.entity.DrillingEntity
import com.siams.kern_prototype.data.db.entity.LayerEntity
import com.siams.kern_prototype.data.db.entity.TableEntity

/** Стол со своими слоями. */
data class TableWithLayers(
    @Embedded val table: TableEntity,
    @Relation(parentColumn = "id", entityColumn = "tableId")
    val layers: List<LayerEntity>,
)

/** Долбление со столами (без слоёв) — для списка/карточек. */
data class DrillingWithTables(
    @Embedded val drilling: DrillingEntity,
    @Relation(parentColumn = "id", entityColumn = "drillingId")
    val tables: List<TableEntity>,
)

/** Полное дерево долбления — для отправки на сервер (§8.3). */
data class DrillingWithContent(
    @Embedded val drilling: DrillingEntity,
    @Relation(
        entity = TableEntity::class,
        parentColumn = "id",
        entityColumn = "drillingId",
    )
    val tables: List<TableWithLayers>,
)
