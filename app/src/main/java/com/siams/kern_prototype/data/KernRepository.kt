package com.siams.kern_prototype.data

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.siams.kern_prototype.data.db.KernDatabase
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
import kotlinx.coroutines.NonCancellable
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
    private val db: KernDatabase,
    private val drillingDao: DrillingDao,
    private val tableDao: TableDao,
    private val layerDao: LayerDao,
    private val fileStorage: FileStorage,
    private val passportCodec: PassportCodec,
    private val json: Json,
) {
    /** R21: источник метрик памяти для замера сохранения слоя (подменяется в тестах). */
    internal var memoryReader: com.siams.kern_prototype.camera.MemoryReader =
        com.siams.kern_prototype.camera.MemoryReader.System

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

    /**
     * Правка паспорта: один адресный UPDATE (паспорт + ревизия + статус), без чтения-копирования
     * записи — параллельные изменения (remoteId, прогресс, статус отправки) не затираются.
     * Пустое название не меняет текущее.
     */
    suspend fun updateDrilling(id: String, title: String, passport: Map<String, String>) =
        drillingDao.updatePassport(id, title.takeIf { it.isNotBlank() }, passportCodec.encode(passport))

    /**
     * Удалить долбление: сначала запись (каскад столов/слоёв), затем каталог его файлов.
     * R05: во время отправки запрещено (проверка в транзакции) — иначе отправка продолжила бы
     * работу со старым снимком данных и оставила частичный проект на S900 без локального архива.
     */
    suspend fun deleteDrilling(id: String) {
        deleteInTransaction(
            body = {
                drillingDao.getById(id)?.also {
                    ensureNotSending(id, "удалить долбление")
                    drillingDao.deleteById(id)
                }
            },
            stillExists = { drillingDao.getById(id) != null },
            cleanup = { fileStorage.deleteDrillingDir(id) },
        )
    }

    /**
     * «Очистить отправленные» (§11): удалить долбление, только если оно ВСЁ ЕЩЁ SENT — статус
     * проверяется в транзакции, а не по ранее полученному списку (его могли изменить или снова
     * отправить). @return true — удалено; false — пропущено (статус изменился или записи нет).
     */
    suspend fun deleteDrillingIfSent(id: String): Boolean = deleteInTransaction(
        body = {
            drillingDao.getById(id)?.takeIf { it.syncStatus == SyncStatus.SENT }?.also { drillingDao.deleteById(id) }
        },
        stillExists = { drillingDao.getById(id) != null },
        cleanup = { fileStorage.deleteDrillingDir(id) },
    )

    fun passportStrings(passportJson: String): Map<String, String> =
        passportCodec.decodeToStrings(passportJson)

    /** R15: ошибки ввода паспорта по ключам полей (пусто — можно сохранять). */
    fun passportErrors(passport: Map<String, String>): Map<String, String> =
        passportCodec.validate(passport)

    // ---- Столы -------------------------------------------------------------

    fun observeTables(drillingId: String): Flow<List<TableWithLayers>> =
        tableDao.observeByDrilling(drillingId)

    fun observeTable(tableId: String): Flow<TableWithLayers?> = tableDao.observeWithLayers(tableId)

    suspend fun getTable(tableId: String): TableEntity? = tableDao.getById(tableId)

    /** Номер следующего стола (max+1) без создания записи — для ленивого показа «Стол №N». */
    suspend fun nextTableNumber(drillingId: String): Int = tableDao.maxNumber(drillingId) + 1

    /** Создать следующий стол (№ = max+1) с новым stackUid. */
    private suspend fun createNextTable(drillingId: String): TableEntity {
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

    /** Номер стола + ревизия долбления — одной транзакцией (R01/R05). */
    suspend fun updateTableNumber(tableId: String, number: Int) = db.withTransaction {
        if (tableDao.setNumber(tableId, number) > 0) markDrillingDirtyByTable(tableId)
        onTransactionBodyEndForTest?.invoke()
    }

    /**
     * Удалить стол: сначала записи (стол + каскад слоёв) и dirty, затем файлы слоёв.
     * R05: во время отправки запрещено (проверка в транзакции).
     */
    suspend fun deleteTable(tableId: String) {
        deleteInTransaction(
            body = {
                tableDao.getById(tableId)?.let { table ->
                    ensureNotSending(table.drillingId, "удалить стол")
                    val files = layerDao.listByTable(tableId).flatMap(::filesOf)
                    tableDao.deleteById(tableId)
                    markDrillingDirty(table.drillingId)
                    files
                }
            },
            stillExists = { tableDao.getById(tableId) != null },
            cleanup = { files -> files.forEach { discardIfUnreferenced(it) } },
        )
    }

    // ---- Слои (снимки) -----------------------------------------------------

    /** Целевой файл для нового снимка (камера пишет JPEG сюда). */
    fun newLayerFile(drillingId: String): Pair<String, File> {
        val layerId = UUID.randomUUID().toString()
        return layerId to fileStorage.layerFile(drillingId, layerId)
    }

    /**
     * Зафиксировать снятый кадр (автосохранение, §4). Если слой такого типа уже есть —
     * пересъёмка: запись переключается на новый файл.
     *
     * R08: порядок «сначала новое, потом удаление старого»:
     *  1) новый JPEG уже целиком на диске (камера пишет атомарно) — проверяем его;
     *  2) миниатюра нового кадра (отдельный файл);
     *  3) одна транзакция Room: стол (создаётся лениво, только при удачном кадре), слой, dirty;
     *  4) ТОЛЬКО после commit удаляются прежний JPEG и миниатюра.
     * Сбой до commit оставляет прежний слой нетронутым; новые файлы без записи удаляются.
     *
     * @param tableId стол, в который шла съёмка; null — создать следующий стол.
     * @return стол, в который записан кадр.
     */
    suspend fun saveCapture(
        drillingId: String,
        tableId: String?,
        layerId: String,
        lampType: LampType,
        file: File,
        profile: CameraProfile,
        coreImageType: CoreImageType = CoreImageType.UNKNOWN,
        audit: com.siams.kern_prototype.camera.CaptureAudit? = null,
    ): TableEntity {
        // R02/R08: кадр целый (полное декодирование) — и его контрольная сумма для сверки перед отправкой.
        // Миниатюра для быстрых превью (§11); при ошибке — используем оригинал.
        // R21: замер памяти сохранения (только для снимков с аудитом — камера Touptek). Весь цикл
        // замера (создание, отметки, завершение) — здесь же на IO: чтение метрик и остановка опроса
        // не выполняются в потоке вызывающего (главном); завершение — в finally, в т.ч. при отмене.
        val prepared = withContext(Dispatchers.IO) {
            val probe = audit?.let { com.siams.kern_prototype.camera.MemoryProbe("save-layer", memoryReader) }
            var outcome = "error"
            try {
                fileStorage.jpegProblem(file)?.let { error("Снимок записан с ошибкой ($it) — повторите съёмку") }
                probe?.mark("validated")
                val sha = fileStorage.sha256(file)
                probe?.mark("sha256")
                val thumb = fileStorage.thumbFileFor(file)
                val thumbPath = if (fileStorage.writeThumbnail(file, thumb)) thumb.absolutePath else null
                outcome = "ok"
                PreparedLayerFiles(sha, thumbPath, probe?.finish(outcome))
            } finally {
                probe?.finish(outcome)
            }
        }
        val sha256 = prepared.sha256
        val thumbPath = prepared.thumbPath
        val profileJson = json.encodeToString(CameraProfile.serializer(), profile)
        val auditJson = audit?.copy(saveMemory = prepared.memory)?.let { json.encodeToString(com.siams.kern_prototype.camera.CaptureAudit.serializer(), it) }
        // Заполняются в конце тела транзакции. Исключение из withTransaction (в т.ч. отмена
        // корутины) НЕ доказывает откат: commit мог состояться до доставки результата.
        var table: TableEntity? = null
        var replaced: LayerEntity? = null
        try {
            db.withTransaction {
                val t = tableId?.let { tableDao.getById(it) } ?: createNextTable(drillingId)
                val existing = layerDao.getByTableAndLamp(t.id, lampType.name)
                // R05: пока долбление отправляется, заменить уже снятый кадр нельзя — ответ
                // идущей загрузки относится к прежнему файлу. Проверка здесь, в транзакции.
                if (existing != null) ensureNotSending(t.drillingId)
                layerDao.upsert(
                    LayerEntity(
                        id = existing?.id ?: layerId,
                        tableId = t.id,
                        lampType = lampType,
                        filePath = file.absolutePath,
                        thumbPath = thumbPath,
                        cameraProfileJson = profileJson,
                        capturedAt = System.currentTimeMillis(),
                        coreImageType = coreImageType,
                        kernUpperDepth = existing?.kernUpperDepth,
                        kernLowerDepth = existing?.kernLowerDepth,
                        remoteImageId = null, // перезаливать заново
                        staleRemoteImageId = existing?.staleRemoteImageId, // прежний кадр всё ещё на сервере
                        fileSha256 = sha256,
                        captureAuditJson = auditJson,
                    ),
                )
                markDrillingDirty(t.drillingId)
                table = t
                replaced = existing
                onTransactionBodyEndForTest?.invoke()
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) { settleUncertainSave(file, thumbPath, replaced) }
            throw t
        }
        deleteReplacedFiles(replaced, file.absolutePath, thumbPath)
        return table!!
    }

    /**
     * R08: итог сохранения неизвестен (ошибка или отмена на границе commit) — выясняем по БД.
     * Кадр зафиксирован → доводим уборку прежних файлов; не зафиксирован → удаляем новые;
     * БД не ответила → ничего не удаляем (лишний файл безопаснее ссылки на удалённый).
     */
    private suspend fun settleUncertainSave(file: File, thumbPath: String?, replaced: LayerEntity?) {
        val committed = runCatching { layerDao.countReferencing(file.absolutePath) > 0 }.getOrNull() ?: return
        if (committed) {
            deleteReplacedFiles(replaced, file.absolutePath, thumbPath)
        } else {
            fileStorage.deleteFile(file.absolutePath)
            thumbPath?.let { discardIfUnreferenced(File(it)) }
        }
    }

    private suspend fun deleteReplacedFiles(replaced: LayerEntity?, newPath: String, newThumb: String?) {
        if (replaced == null) return
        if (replaced.filePath != newPath) discardIfUnreferenced(File(replaced.filePath))
        replaced.thumbPath?.takeIf { it != newThumb }?.let { discardIfUnreferenced(File(it)) }
    }

    /**
     * Удалить файл, только если ни один слой на него не ссылается (R08). Не отменяется.
     * Если проверить не удалось — файл остаётся.
     */
    suspend fun discardIfUnreferenced(file: File) = withContext(NonCancellable) {
        val refs = runCatching { layerDao.countReferencing(file.absolutePath) }.getOrNull() ?: return@withContext
        if (refs == 0) fileStorage.deleteFile(file.absolutePath)
    }

    /**
     * R05: пока долбление в отправке (в т.ч. в очереди и между повторами), снятые кадры нельзя
     * заменять и удалять: ответ идущей загрузки относится к ним, а удалённый локально кадр
     * остался бы на сервере при последующем SENT.
     */
    private suspend fun ensureNotSending(drillingId: String, action: String = "переснять кадр") {
        check(drillingDao.getById(drillingId)?.syncStatus != SyncStatus.SENDING) {
            "Долбление отправляется — $action можно после завершения отправки"
        }
    }

    /** Для экрана съёмки: проверить до снимка, можно ли заменить слой [lampType] стола [tableId]. */
    suspend fun checkCanSaveCapture(tableId: String?, lampType: LampType) {
        tableId ?: return
        val table = tableDao.getById(tableId) ?: return
        if (layerDao.getByTableAndLamp(tableId, lampType.name) != null) ensureNotSending(table.drillingId)
    }

    /** Кадр не дошёл до фиксации: убрать его JPEG и миниатюру, если БД на них не ссылается. */
    suspend fun discardUncommittedCapture(file: File) {
        discardIfUnreferenced(file)
        discardIfUnreferenced(fileStorage.thumbFileFor(file))
    }

    /** Точка для тестов отмены на границе commit; в приложении всегда null. */
    @VisibleForTesting
    internal var onTransactionBodyEndForTest: (() -> Unit)? = null

    /**
     * Удалить слой: сначала запись и dirty, затем его файлы.
     * R05: во время отправки запрещено (проверка в транзакции).
     */
    suspend fun deleteLayer(layerId: String) {
        deleteInTransaction(
            body = {
                layerDao.getById(layerId)?.let { layer ->
                    tableDao.getById(layer.tableId)?.let { ensureNotSending(it.drillingId, "удалить кадр") }
                    layerDao.deleteById(layerId)
                    markDrillingDirtyByTable(layer.tableId)
                    filesOf(layer)
                }
            },
            stillExists = { layerDao.getById(layerId) != null },
            cleanup = { files -> files.forEach { discardIfUnreferenced(it) } },
        )
    }

    private fun filesOf(layer: LayerEntity): List<File> =
        listOfNotNull(File(layer.filePath), layer.thumbPath?.let(::File))

    /**
     * R08 для удаления: сначала транзакция БД, затем файлы — запись никогда не ссылается на
     * уже удалённый файл. [body] удаляет записи и возвращает то, что нужно убрать с диска
     * (null — удалять нечего). Исключение (в т.ч. отмена) не доказывает откат: если тело
     * транзакции завершилось, исход выясняется по БД ([stillExists]); удалено → [cleanup];
     * не удалено или выяснить не удалось → файлы сохраняются. Ошибка удаления файла после
     * commit операцию не отменяет: остаётся лишний файл (см. [FileStorage.deleteFile]).
     * @return true — записи удалены; false — удалять было нечего.
     */
    private suspend fun <T : Any> deleteInTransaction(
        body: suspend () -> T?,
        stillExists: suspend () -> Boolean,
        cleanup: suspend (T) -> Unit,
    ): Boolean {
        var removed: T? = null
        try {
            db.withTransaction {
                removed = body()
                onTransactionBodyEndForTest?.invoke()
            }
        } catch (t: Throwable) {
            withContext(NonCancellable) {
                val r = removed ?: return@withContext // тело не завершилось — commit не было
                val exists = runCatching { stillExists() }.getOrNull() ?: return@withContext
                if (!exists) cleanup(r)
            }
            throw t
        }
        removed?.let { cleanup(it) }
        return removed != null
    }

    /**
     * Глубины стола + ревизия долбления — одной транзакцией (R01/R05). Адресный UPDATE: прочие
     * поля слоёв (в т.ч. remoteImageId, записываемый отправкой) не перезаписываются копией.
     * Метаданные нужно снова доставить PUT'ом (R01).
     */
    suspend fun setLayerDepth(tableId: String, upper: Double?, lower: Double?) = db.withTransaction {
        layerDao.setDepthByTable(tableId, upper, lower)
        markDrillingDirtyByTable(tableId)
        onTransactionBodyEndForTest?.invoke()
    }

    private suspend fun markDrillingDirtyByTable(tableId: String) {
        val table = tableDao.getById(tableId) ?: return
        markDrillingDirty(table.drillingId)
    }

    /**
     * Любая правка: +1 к ревизии (R01/R05); отправленное/ошибочное долбление → снова
     * «Не отправлено» (§9).
     */
    private suspend fun markDrillingDirty(drillingId: String) = drillingDao.markEdited(drillingId)

    // ---- Синхронизация -----------------------------------------------------

    suspend fun getDrillingContent(id: String): DrillingWithContent? = drillingDao.getWithContent(id)

    suspend fun listDrillingIds(status: SyncStatus): List<String> = drillingDao.listIdsByStatus(status)

    /** См. [DrillingDao.failIfSending]. */
    suspend fun failIfSending(id: String, error: String): Boolean = drillingDao.failIfSending(id, error) > 0

    suspend fun setSyncStatus(id: String, status: SyncStatus, error: String? = null) =
        drillingDao.setSyncStatus(id, status, error)

    /** R03: проект есть в назначении [destination] (ключ [S900Destination.key]). */
    suspend fun setRemoteSource(id: String, remoteId: String, destination: String) =
        drillingDao.setRemoteSource(id, remoteId, destination)

    /** R03: см. [DrillingDao.confirmRemoteDestination]. */
    suspend fun confirmRemoteDestination(id: String, destination: String): Boolean =
        drillingDao.confirmRemoteDestination(id, destination) > 0

    /**
     * R03: удалённое состояние долбления недействительно (сменился сервер/учётная запись или
     * проект удалён на сервере): сбросить id проекта и всех слоёв одной транзакцией — следующая
     * отправка выполнит полный экспорт. Не правка пользователя: ревизия не меняется.
     */
    suspend fun resetRemoteState(id: String) = db.withTransaction {
        drillingDao.clearRemoteSource(id)
        drillingDao.clearRemoteLayers(id)
    }

    /** См. [DrillingDao.markSentIfComplete]. */
    suspend fun markSentIfComplete(id: String, revision: Long): Boolean =
        drillingDao.markSentIfComplete(id, revision) > 0

    suspend fun countUndeliveredLayers(drillingId: String): Int = drillingDao.countUndeliveredLayers(drillingId)

    suspend fun setUploadProgress(id: String, uploaded: Int, total: Int) =
        drillingDao.setUploadProgress(id, uploaded, total)

    /**
     * R05: записать id загруженного изображения в ту версию слоя, файл которой загружался
     * ([layer] — копия на момент загрузки). false — слой переснят во время POST: id не записан.
     */
    suspend fun bindRemoteImage(layer: LayerEntity, remoteId: String): Boolean =
        layerDao.setRemoteImageId(layer.id, layer.filePath, remoteId) > 0

    suspend fun markStaleUpload(layerId: String, remoteId: String) = layerDao.markStaleUpload(layerId, remoteId)

    /** R01: см. [LayerDao.confirmMeta]; [layer] — копия, из которой собран отправленный PUT. */
    suspend fun confirmLayerMeta(layer: LayerEntity, remoteImageId: String): Boolean =
        layerDao.confirmMeta(
            id = layer.id,
            filePath = layer.filePath,
            remoteImageId = remoteImageId,
            coreImageType = layer.coreImageType.name,
            upper = layer.kernUpperDepth,
            lower = layer.kernLowerDepth,
        ) > 0

    fun usedBytes(): Long = fileStorage.usedBytes()
}

/** Результат подготовки файлов слоя перед записью в БД (R21: с замером памяти). */
private class PreparedLayerFiles(
    val sha256: String,
    val thumbPath: String?,
    val memory: com.siams.kern_prototype.camera.MemoryTrace?,
)
