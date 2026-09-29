package com.siams.kern_prototype.sync

import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.entity.LayerEntity
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.network.S900Api
import com.siams.kern_prototype.network.dto.ImageAttributesDto
import com.siams.kern_prototype.network.dto.SourceImageUpdateDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.File
import javax.inject.Inject

/**
 * Реализация последовательности отправки одного долбления (§8.3).
 * Столы заливаются ПОСЛЕДОВАТЕЛЬНО (шаг 3 полагается на «стол = последний FoV»).
 * Идемпотентность: create source по id; слой не перезаливается, если есть remoteImageId (§9).
 */
class DrillingUploader @Inject constructor(
    private val api: S900Api,
    private val repository: KernRepository,
    private val passportCodec: PassportCodec,
) {
    /** @throws Exception при сетевой/серверной ошибке — для ретрая WorkManager. */
    suspend fun upload(drillingId: String) {
        // R05: SENDING — ДО чтения содержимого. С этого момента репозиторий не даёт заменить
        // снятые кадры, поэтому прочитанные ниже файлы не сменятся до конца отправки.
        repository.setSyncStatus(drillingId, SyncStatus.SENDING, null)
        val content = repository.getDrillingContent(drillingId)
            ?: error("Долбление не найдено: $drillingId")
        val drilling = content.drilling

        // R05: на сервере остался кадр прежней версии слоя — автоматическую замену не считаем
        // выполненной (контракт замены на S900 не согласован, R04). Ничего не отправляем.
        content.tables.forEach { t ->
            t.layers.firstOrNull { it.staleRemoteImageId != null }?.let {
                error(
                    "Стол №${t.table.number}, ${it.lampType.ruLabel}: кадр переснят во время прошлой " +
                        "отправки, на сервере остался прежний кадр (${it.staleRemoteImageId}). " +
                        "Автоматическая замена не выполняется.",
                )
            }
        }

        val tables = content.tables.sortedBy { it.table.number }
        repository.setUploadProgress(drillingId, 0, tables.size)

        // 1. Создать (первая отправка) или обновить паспорт (повторная — POST атрибуты
        //    существующего проекта не обновляет, §8.5, нужен PUT).
        val body = buildSourceBody(drilling.id, drilling.title, drilling.passportJson)
        if (drilling.remoteId == null) {
            api.createSource(body).ensureSuccess("POST /analysis/source")
            repository.setDrillingRemoteId(drillingId, drilling.id)
        } else {
            // Повторная отправка: обновляем паспорт через PUT. Если на этом сервере источника
            // с таким id нет (сменили сервер / его удалили) — PUT даёт 404. Тогда откатываемся
            // на идемпотентный POST create (создаст источник заново по тому же id).
            val put = api.updateSource(drilling.id, body)
            if (put.code() == 404) {
                put.errorBody()?.close()
                api.createSource(body).ensureSuccess("POST /analysis/source (после 404 на PUT)")
                repository.setDrillingRemoteId(drillingId, drilling.id)
            } else {
                put.ensureSuccess("PUT /analysis/source/{id}")
            }
        }

        // 2..6. Столы по порядку номера, с обновлением прогресса.
        tables.forEachIndexed { index, table ->
            uploadTable(drilling.id, table)
            repository.setUploadProgress(drillingId, index + 1, tables.size)
        }

        // R01/R05: SENT — одним условным запросом (ревизия не изменилась И все слои доставлены).
        // Иначе — ошибка попытки: UploadWorker повторит отправку (новые правки/недоставленные
        // метаданные уйдут), а после исчерпания попыток долбление получит ERROR с этим текстом.
        if (!repository.markSentIfComplete(drillingId, drilling.editRevision)) {
            val undelivered = repository.countUndeliveredLayers(drillingId)
            error(
                if (undelivered > 0) "Отправка не завершена: не подтверждено слоёв на сервере — $undelivered"
                else "Долбление изменено во время отправки — изменения будут отправлены повторно",
            )
        }
    }

    private suspend fun uploadTable(sourceId: String, table: TableWithLayers) {
        val ordered = listOf(LampType.DAYLIGHT, LampType.UF)
            .mapNotNull { lamp -> table.layers.firstOrNull { it.lampType == lamp } }
            .filter { File(it.filePath).exists() }
        if (ordered.isEmpty()) return

        var fovIdx: Int? = null
        for (layer in ordered) {
            // Уже залит — не перезаливаем; убеждаемся, что знаем fovIdx.
            if (layer.remoteImageId != null) {
                if (fovIdx == null) fovIdx = findFovIdx(sourceId, layer.remoteImageId)
                // R01: файл принят раньше, но метаданные не подтверждены (сбой PUT или правка
                // глубин после отправки) — доставляем только PUT.
                if (!layer.metaConfirmed) putMeta(sourceId, layer.remoteImageId, layer, table.table.stackUid)
                continue
            }

            val part = filePart(layer.filePath)
            val imageId = if (fovIdx == null) {
                parseImageId(api.uploadImageNewFov(sourceId, part).ensureOk("upload new FoV"))
            } else {
                parseImageId(api.uploadImageToFov(sourceId, fovIdx, part).ensureOk("upload to FoV $fovIdx"))
            }
            // R05: id — только в ту версию слоя, файл которой загружен. Если слой переснят во
            // время POST, ответ старой загрузки не подтверждает новый кадр: помечаем, что на
            // сервере остался прежний кадр, и останавливаем отправку (без SENT).
            if (!repository.bindRemoteImage(layer, imageId)) {
                repository.markStaleUpload(layer.id, imageId)
                error(
                    "Стол №${table.table.number}, ${layer.lampType.ruLabel}: кадр переснят во время загрузки; " +
                        "на сервере остался прежний кадр ($imageId). Автоматическая замена не выполняется.",
                )
            }

            if (fovIdx == null) {
                fovIdx = findFovIdx(sourceId, imageId) ?: (fovSize(sourceId) - 1).coerceAtLeast(0)
            }

            putMeta(sourceId, imageId, layer, table.table.stackUid)
        }
    }

    /** PUT метаданных слоя + подтверждение в Room (R01). Формат запроса не изменён. */
    private suspend fun putMeta(sourceId: String, imageId: String, layer: LayerEntity, stackUid: String) {
        api.updateImageMeta(sourceId, imageId, metaOf(layer, stackUid))
            .ensureSuccess("PUT image meta")
        // false — слой изменён во время PUT: подтверждения нет, итог отправки не станет SENT.
        repository.confirmLayerMeta(layer, imageId)
    }

    // ---- helpers -----------------------------------------------------------

    private fun buildSourceBody(id: String, title: String, passportJson: String): JsonObject {
        val passport = passportCodec.toJsonObject(passportJson)
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            put("title", JsonPrimitive(title))
            // Гео-атрибуты — плоские ключи верхнего уровня (§8.3, @JsonAnySetter).
            passport.forEach { (k, v) -> put(k, v) }
            put("app_version", JsonPrimitive("kern-android/1.0"))
            put("postupload_processing", JsonPrimitive(true)) // §10.5
        }
    }

    private fun metaOf(layer: LayerEntity, stackUid: String) = SourceImageUpdateDto(
        attributes = ImageAttributesDto(
            lampType = layer.lampType.name,
            stackUid = stackUid,
            coreImageType = layer.coreImageType.name,
            kernUpperDepth = layer.kernUpperDepth,
            kernLowerDepth = layer.kernLowerDepth,
        ),
    )

    private fun filePart(path: String): MultipartBody.Part {
        val file = File(path)
        val requestBody = file.asRequestBody("image/jpeg".toMediaType())
        return MultipartBody.Part.createFormData("file", file.name, requestBody)
    }

    private suspend fun findFovIdx(sourceId: String, imageId: String): Int? {
        val source = api.getSource(sourceId).ensureOk("GET source")
        source.fieldOfViews.forEachIndexed { index, fov ->
            if (fov.sourceImages.any { it.id == imageId }) return fov.idx ?: index
        }
        return null
    }

    private suspend fun fovSize(sourceId: String): Int =
        api.getFovSize(sourceId).ensureOk("GET fovs/size")

    private fun parseImageId(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.content
        is JsonObject -> el["id"]?.jsonPrimitive?.content ?: el.toString()
        else -> el.toString()
    }

    private fun <T> Response<T>.ensureOk(label: String): T {
        if (!isSuccessful) {
            error("$label → HTTP ${code()} ${errorText(errorBody()?.string())}")
        }
        return body() ?: error("$label → пустой ответ")
    }

    /** Для ответов без тела (create возвращает id строкой, PUT meta — пусто). */
    private fun Response<ResponseBody>.ensureSuccess(label: String) {
        if (!isSuccessful) {
            error("$label → HTTP ${code()} ${errorText(errorBody()?.string())}")
        }
        body()?.close()
    }

    /** s900 отдаёт ошибки HTML-страницей — вырезаем теги, оставляем читаемый текст. */
    private fun errorText(raw: String?): String =
        raw.orEmpty().replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(200)
}
