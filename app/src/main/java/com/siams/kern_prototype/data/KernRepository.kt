package com.siams.kern_prototype.data

import com.siams.kern_prototype.data.db.dao.DrillingDao
import com.siams.kern_prototype.data.db.dao.LayerDao
import com.siams.kern_prototype.data.db.dao.TableDao
import com.siams.kern_prototype.data.db.entity.DrillingEntity
import com.siams.kern_prototype.data.db.entity.LayerEntity
import com.siams.kern_prototype.data.db.entity.TableEntity
import com.siams.kern_prototype.data.db.relation.DrillingWithContent
import com.siams.kern_prototype.data.db.relation.DrillingWithTables
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.domain.model.CameraProfile
import com.siams.kern_prototype.domain.model.CoreImageType
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.domain.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единый источник истины над Room + файлами (§4, §13). Автосохранение: каждое
 * изменение немедленно пишется в Room, отдельной кнопки «Сохранить» нет (§4).
 */
@Singleton
class KernRepository @Inject constructor(
    private val drillingDao: DrillingDao,
    private val tableDao: TableDao,
    private val layerDao: LayerDao,
    private val fileStorage: FileStorage,
    private val passportCodec: PassportCodec,
    private val json: Json,
) {
    // ---- Долбления ---------------------------------------------------------

    fun observeDrillings(): Flow<List<DrillingWithTables>> = drillingDao.observeAllWithTables()

    fun observeDrilling(id: String): Flow<DrillingEntity?> = drillingDao.observeById(id)

    suspend fun getDrilling(id: String): DrillingEntity? = drillingDao.getById(id)

    /** Создать долбление; id = UUID (он же id AnalysisSource, §7). */
    suspend fun createDrilling(title: String, passport: Map<String, String>): String {
        val id = UUID.randomUUID().toString()
        drillingDao.upsert(
            DrillingEntity(
                id = id,
                title = title.ifBlank { "Долбление" },
                passportJson = passportCodec.encode(passport),
                createdAt = System.currentTimeMillis(),
                syncStatus = SyncStatus.NOT_SENT,
            ),
        )
        return id
    }

    suspend fun updateDrilling(id: String, title: String, passport: Map<String, String>) {
        val existing = drillingDao.getById(id) ?: return
        drillingDao.update(
            existing.copy(
                title = title.ifBlank { existing.title },
                passportJson = passportCodec.encode(passport),
                // изменение паспорта → снова требуется отправка
                syncStatus = if (existing.syncStatus == SyncStatus.SENT) SyncStatus.NOT_SENT else existing.syncStatus,
            ),
        )
    }

    suspend fun deleteDrilling(id: String) {
        drillingDao.deleteById(id)          // каскад в Room удалит столы/слои
        fileStorage.deleteDrillingDir(id)   // и файлы
    }

    fun passportStrings(passportJson: String): Map<String, String> =
        passportCodec.decodeToStrings(passportJson)

    // ---- Столы -------------------------------------------------------------

    fun observeTables(drillingId: String): Flow<List<TableWithLayers>> =
        tableDao.observeByDrilling(drillingId)

    fun observeTable(tableId: String): Flow<TableWithLayers?> = tableDao.observeWithLayers(tableId)

    suspend fun getTable(tableId: String): TableEntity? = tableDao.getById(tableId)

    /** Номер следующего стола (max+1) без создания записи — для ленивого показа «Стол №N». */
    suspend fun nextTableNumber(drillingId: String): Int = tableDao.maxNumber(drillingId) + 1

    /** Создать следующий стол (№ = max+1) с новым stackUid. */
    suspend fun createNextTable(drillingId: String): TableEntity {
        val number = tableDao.maxNumber(drillingId) + 1
        val table = TableEntity(
            id = UUID.randomUUID().toString(),
            drillingId = drillingId,
            number = number,
            stackUid = UUID.randomUUID().toString(),
        )
        tableDao.upsert(table)
        return table
    }

    /** Есть ли у стола хотя бы один снятый слой. */
    suspend fun tableIsEmpty(tableId: String): Boolean = layerDao.listByTable(tableId).isEmpty()

    suspend fun updateTableNumber(tableId: String, number: Int) {
        val t = tableDao.getById(tableId) ?: return
        tableDao.update(t.copy(number = number))
        markDrillingDirtyByTable(tableId)
    }

    suspend fun deleteTable(tableId: String) {
        val table = tableDao.getById(tableId)
        val layers = layerDao.listByTable(tableId)
        layers.forEach {
            fileStorage.deleteFile(it.filePath)
            it.thumbPath?.let { t -> fileStorage.deleteFile(t) }
        }
        tableDao.deleteById(tableId)
        table?.let { markDrillingDirty(it.drillingId) }
    }

    // ---- Слои (снимки) -----------------------------------------------------

    /** Целевой файл для нового снимка (камера пишет JPEG сюда). */
    fun newLayerFile(drillingId: String): Pair<String, File> {
        val layerId = UUID.randomUUID().toString()
        return layerId to fileStorage.layerFile(drillingId, layerId)
    }

    /**
     * Сохранить снятый слой. Если слой такого типа уже есть — переснимаем
     * (заменяем файл и запись). Автосохранение (§4).
     */
    suspend fun saveLayer(
        layerId: String,
        tableId: String,
        lampType: LampType,
        file: File,
        profile: CameraProfile,
        coreImageType: CoreImageType = CoreImageType.UNKNOWN,
    ) {
        val existing = layerDao.getByTableAndLamp(tableId, lampType.name)
        if (existing != null && existing.filePath != file.absolutePath) {
            fileStorage.deleteFile(existing.filePath)
            existing.thumbPath?.let { fileStorage.deleteFile(it) }
        }
        // Миниатюра для быстрых превью (§11); при ошибке — используем оригинал.
        // Декодирование JPEG — на IO, чтобы не блокировать UI-поток (защита от ANR).
        val thumb = fileStorage.thumbFileFor(file)
        val thumbPath = withContext(Dispatchers.IO) {
            if (fileStorage.writeThumbnail(file, thumb)) thumb.absolutePath else null
        }
        layerDao.upsert(
            LayerEntity(
                id = existing?.id ?: layerId,
                tableId = tableId,
                lampType = lampType,
                filePath = file.absolutePath,
                thumbPath = thumbPath,
                cameraProfileJson = json.encodeToString(CameraProfile.serializer(), profile),
                capturedAt = System.currentTimeMillis(),
                coreImageType = coreImageType,
                kernUpperDepth = existing?.kernUpperDepth,
                kernLowerDepth = existing?.kernLowerDepth,
                remoteImageId = null, // перезаливать заново
            ),
        )
        markDrillingDirtyByTable(tableId)
    }

    suspend fun deleteLayer(layerId: String) {
        val layer = layerDao.getById(layerId) ?: return
        fileStorage.deleteFile(layer.filePath)
        layer.thumbPath?.let { fileStorage.deleteFile(it) }
        layerDao.deleteById(layerId)
        markDrillingDirtyByTable(layer.tableId)
    }

    suspend fun setLayerDepth(tableId: String, upper: Double?, lower: Double?) {
        listOf(LampType.DAYLIGHT, LampType.UF).forEach { lamp ->
            layerDao.getByTableAndLamp(tableId, lamp.name)?.let {
                layerDao.update(it.copy(kernUpperDepth = upper, kernLowerDepth = lower))
            }
        }
        markDrillingDirtyByTable(tableId)
    }

    private suspend fun markDrillingDirtyByTable(tableId: String) {
        val table = tableDao.getById(tableId) ?: return
        markDrillingDirty(table.drillingId)
    }

    /** Любая правка отправленного/ошибочного долбления → снова «Не отправлено» (§9). */
    private suspend fun markDrillingDirty(drillingId: String) {
        val drilling = drillingDao.getById(drillingId) ?: return
        if (drilling.syncStatus == SyncStatus.SENT || drilling.syncStatus == SyncStatus.ERROR) {
            drillingDao.setSyncStatus(drilling.id, SyncStatus.NOT_SENT, null)
        }
    }

    // ---- Синхронизация -----------------------------------------------------

    suspend fun getDrillingContent(id: String): DrillingWithContent? = drillingDao.getWithContent(id)

    suspend fun setSyncStatus(id: String, status: SyncStatus, error: String? = null) =
        drillingDao.setSyncStatus(id, status, error)

    suspend fun setDrillingRemoteId(id: String, remoteId: String) =
        drillingDao.setRemoteId(id, remoteId)

    suspend fun setUploadProgress(id: String, uploaded: Int, total: Int) =
        drillingDao.setUploadProgress(id, uploaded, total)

    suspend fun setRemoteImageId(layerId: String, remoteId: String) =
        layerDao.setRemoteImageId(layerId, remoteId)

    fun usedBytes(): Long = fileStorage.usedBytes()
}
