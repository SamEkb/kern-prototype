package com.siams.kern_prototype.sync

import com.siams.kern_prototype.data.KernRepository
import com.siams.kern_prototype.data.PassportCodec
import com.siams.kern_prototype.data.db.entity.LayerEntity
import com.siams.kern_prototype.data.db.relation.DrillingWithContent
import com.siams.kern_prototype.data.db.relation.TableWithLayers
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.data.storage.FileStorage
import com.siams.kern_prototype.domain.model.LampType
import com.siams.kern_prototype.domain.model.S900Destination
import com.siams.kern_prototype.domain.model.SyncStatus
import com.siams.kern_prototype.network.S900Api
import com.siams.kern_prototype.network.dto.ImageAttributesDto
import com.siams.kern_prototype.network.dto.SourceImageUpdateDto
import kotlinx.coroutines.flow.first
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
/**
 * Отправка не может завершиться повтором без действий пользователя (повреждён локальный архив,
 * на сервере осталось расхождение, требующее решения). [UploadWorker] ставит ERROR сразу.
 */
class UploadRejectedException(message: String) : IllegalStateException(message)

class DrillingUploader @Inject constructor(
    private val api: S900Api,
    private val repository: KernRepository,
    private val passportCodec: PassportCodec,
    private val settingsStore: ServerSettingsStore,
    private val fileStorage: FileStorage,
) {
    /**
     * @throws UploadRejectedException — повтор не поможет (см. класс);
     * @throws Exception прочие сетевые/серверные ошибки — для ретрая WorkManager.
     */
    suspend fun upload(drillingId: String) {
        // R03: назначение фиксируется на всю задачу; каждый запрос несёт его в теге.
        val settings = settingsStore.settings.first()
        if (!settings.isConfigured) throw UploadRejectedException("Сервер s900 не настроен")
        val dest = settings.destination()

        // R05: SENDING — ДО чтения содержимого. С этого момента репозиторий не даёт заменить
        // или удалить снятые кадры, поэтому прочитанные ниже файлы не сменятся до конца отправки.
        repository.setSyncStatus(drillingId, SyncStatus.SENDING, null)
        reconcileDestination(drillingId, dest)
        var content = readValidated(drillingId)
        val drilling = content.drilling

        // 1. Создать (первая отправка) или обновить паспорт (повторная — POST атрибуты
        //    существующего проекта не обновляет, §8.5, нужен PUT).
        val body = buildSourceBody(drilling.id, drilling.title, drilling.passportJson)
        if (drilling.remoteId == null) {
            api.createSource(dest, body).ensureSuccess("POST /analysis/source")
            repository.setRemoteSource(drillingId, drilling.id, dest.key)
        } else {
            val put = api.updateSource(dest, drilling.id, body)
            if (put.code() == 404) {
                // R03: проекта с этим id в назначении нет (удалён на сервере). Прежние id
                // изображений здесь недействительны — полный экспорт в заново созданный проект.
                put.errorBody()?.close()
                repository.resetRemoteState(drillingId)
                content = readValidated(drillingId)
                api.createSource(dest, body).ensureSuccess("POST /analysis/source (проект не найден на сервере)")
                repository.setRemoteSource(drillingId, drilling.id, dest.key)
            } else {
                put.ensureSuccess("PUT /analysis/source/{id}")
            }
        }

        // 2..6. Столы по порядку номера, с обновлением прогресса.
        val tables = content.tables.sortedBy { it.table.number }
        repository.setUploadProgress(drillingId, 0, tables.size)
        tables.forEachIndexed { index, table ->
            uploadTable(dest, drilling.id, table)
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

    /**
     * R03: удалённые id действительны только в своём назначении. Другой сервер/учётная запись:
     *  - проекта там нет (404) — удалённое состояние сбрасывается, полный экспорт в новое
     *    назначение (прежнее не трогается);
     *  - проект там уже есть — его содержимое неизвестно (отправлялся раньше): досылка вслепую
     *    дала бы дубли, а прежние id могли бы совпасть с чужими кадрами — отказ без сброса
     *    (следующая попытка снова это распознает). Нужна сверка/решение (R04, Q06).
     * Запись до v5 (назначение неизвестно) — отдельное состояние: текущий сервер не считается
     * доказанным (id могли бы совпасть с чужими), автоматически не перезаливаем. Нужно явное
     * подтверждение исходного сервера оператором ([KernRepository.confirmRemoteDestination]).
     */
    private suspend fun reconcileDestination(drillingId: String, dest: S900Destination) {
        val d = repository.getDrilling(drillingId) ?: throw UploadRejectedException("Долбление не найдено")
        val remoteId = d.remoteId ?: return
        when (d.remoteDestination) {
            dest.key -> Unit
            null -> throw UploadRejectedException(UNKNOWN_DESTINATION)
            else -> {
                val existing = api.getSource(dest, remoteId)
                if (existing.code() != 404) {
                    existing.ensureOk("GET source (новое назначение)")
                    throw UploadRejectedException(
                        "Сервер отправки изменён, а на новом сервере уже есть проект этого долбления с " +
                            "неизвестным содержимым. Автоматическая досылка не выполняется — нужна сверка.",
                    )
                }
                existing.errorBody()?.close()
                repository.resetRemoteState(drillingId)
            }
        }
    }

    /**
     * Содержимое для отправки + R02: всё, что предстоит загрузить, должно быть на месте и целым.
     * Иначе отправка не начинается вовсе (без частичной выгрузки и без ложного SENT); уже принятые
     * сервером слои оцениваются по подтверждённому состоянию, а не по наличию файла.
     */
    private suspend fun readValidated(drillingId: String): DrillingWithContent {
        val content = repository.getDrillingContent(drillingId) ?: throw UploadRejectedException("Долбление не найдено")
        val problems = mutableListOf<String>()
        content.tables.sortedBy { it.table.number }.forEach { t ->
            t.layers.sortedBy { it.lampType.ordinal }.forEach { layer ->
                val where = "Стол №${t.table.number}, ${layer.lampType.ruLabel}"
                // R05: на сервере остался кадр прежней версии слоя — автоматическую замену не
                // считаем выполненной (контракт замены на S900 не согласован, R04).
                if (layer.staleRemoteImageId != null) {
                    problems += "$where: кадр переснят во время прошлой отправки, на сервере остался " +
                        "прежний кадр (${layer.staleRemoteImageId}); автоматическая замена не выполняется"
                } else if (layer.remoteImageId == null) {
                    fileProblem(layer)?.let { problems += "$where: $it" }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw UploadRejectedException(
                problems.take(MAX_LISTED).joinToString("; ") +
                    if (problems.size > MAX_LISTED) " (и ещё ${problems.size - MAX_LISTED})" else "",
            )
        }
        return content
    }

    /**
     * R02: файл снимка на месте и цел. Есть сумма со съёмки — сверка SHA-256 (ловит любую порчу);
     * снимок до v5 — полное декодирование с отклонением неполного/повреждённого изображения.
     */
    private fun fileProblem(layer: LayerEntity): String? {
        val f = File(layer.filePath)
        val expected = layer.fileSha256
        return when {
            !f.isFile -> "файл снимка отсутствует"
            f.length() == 0L -> "файл снимка пуст"
            expected != null -> if (fileStorage.sha256(f) == expected) null else "файл снимка изменён или повреждён"
            else -> fileStorage.jpegProblem(f)
        }
    }

    private suspend fun uploadTable(dest: S900Destination, sourceId: String, table: TableWithLayers) {
        val ordered = listOf(LampType.DAYLIGHT, LampType.UF)
            .mapNotNull { lamp -> table.layers.firstOrNull { it.lampType == lamp } }
        if (ordered.isEmpty()) return

        // Каждый ранее принятый кадр стола сверяется с проектом (одним чтением): он должен быть на
        // месте, и все кадры стола — в одном FoV. Расхождение (кадр исчез/перемещён) — R04: без
        // согласованного контракта не «чиним» его догадкой и не объявляем стол доставленным.
        var fovIdx: Int? = null
        val delivered = ordered.filter { it.remoteImageId != null }
        if (delivered.isNotEmpty()) {
            val placement = fovPlacement(dest, sourceId)
            val fovs = delivered.map { layer ->
                placement[layer.remoteImageId] ?: throw UploadRejectedException(
                    "Стол №${table.table.number}, ${layer.lampType.ruLabel}: ранее отправленный кадр " +
                        "(${layer.remoteImageId}) не найден в проекте на сервере",
                )
            }.toSet()
            if (fovs.size > 1) {
                throw UploadRejectedException(
                    "Стол №${table.table.number}: кадры стола на сервере находятся в разных FoV ($fovs)",
                )
            }
            fovIdx = fovs.single()
        }
        for (layer in ordered) {
            // Уже залит (и проверен выше) — не перезаливаем.
            if (layer.remoteImageId != null) {
                // R01: файл принят раньше, но метаданные не подтверждены (сбой PUT или правка
                // глубин после отправки) — доставляем только PUT.
                if (!layer.metaConfirmed) putMeta(dest, sourceId, layer.remoteImageId, layer, table.table.stackUid)
                continue
            }

            val part = filePart(layer.filePath)
            val imageId = if (fovIdx == null) {
                parseImageId(api.uploadImageNewFov(dest, sourceId, part).ensureOk("upload new FoV"))
            } else {
                parseImageId(api.uploadImageToFov(dest, sourceId, fovIdx, part).ensureOk("upload to FoV $fovIdx"))
            }
            // R05: id — только в ту версию слоя, файл которой загружен. Если слой переснят во
            // время POST, ответ старой загрузки не подтверждает новый кадр: помечаем, что на
            // сервере остался прежний кадр, и останавливаем отправку (без SENT).
            if (!repository.bindRemoteImage(layer, imageId)) {
                repository.markStaleUpload(layer.id, imageId)
                throw UploadRejectedException(
                    "Стол №${table.table.number}, ${layer.lampType.ruLabel}: кадр переснят во время загрузки; " +
                        "на сервере остался прежний кадр ($imageId). Автоматическая замена не выполняется.",
                )
            }

            if (fovIdx == null) {
                fovIdx = findFovIdx(dest, sourceId, imageId) ?: (fovSize(dest, sourceId) - 1).coerceAtLeast(0)
            }

            putMeta(dest, sourceId, imageId, layer, table.table.stackUid)
        }
    }

    /** PUT метаданных слоя + подтверждение в Room (R01). Формат запроса не изменён. */
    private suspend fun putMeta(dest: S900Destination, sourceId: String, imageId: String, layer: LayerEntity, stackUid: String) {
        api.updateImageMeta(dest, sourceId, imageId, metaOf(layer, stackUid))
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

    /** id кадра → индекс его FoV в проекте (одно чтение проекта). */
    private suspend fun fovPlacement(dest: S900Destination, sourceId: String): Map<String, Int> {
        val source = api.getSource(dest, sourceId).ensureOk("GET source")
        return buildMap {
            source.fieldOfViews.forEachIndexed { index, fov ->
                fov.sourceImages.forEach { img -> img.id?.let { put(it, fov.idx ?: index) } }
            }
        }
    }

    private suspend fun findFovIdx(dest: S900Destination, sourceId: String, imageId: String): Int? {
        val source = api.getSource(dest, sourceId).ensureOk("GET source")
        source.fieldOfViews.forEachIndexed { index, fov ->
            if (fov.sourceImages.any { it.id == imageId }) return fov.idx ?: index
        }
        return null
    }

    private suspend fun fovSize(dest: S900Destination, sourceId: String): Int =
        api.getFovSize(dest, sourceId).ensureOk("GET fovs/size")

    private fun parseImageId(el: JsonElement): String = when (el) {
        is JsonPrimitive -> el.content
        is JsonObject -> el["id"]?.jsonPrimitive?.content ?: el.toString()
        else -> el.toString()
    }

    private fun <T> Response<T>.ensureOk(label: String): T {
        if (!isSuccessful) error(httpError(label, code(), errorBody()?.string()))
        return body() ?: error("$label → пустой ответ")
    }

    /** Для ответов без тела (create возвращает id строкой, PUT meta — пусто). */
    private fun Response<ResponseBody>.ensureSuccess(label: String) {
        if (!isSuccessful) error(httpError(label, code(), errorBody()?.string()))
        body()?.close()
    }

    /** R12: отказ в доступе называется явно (сессия уже переполучена интерцептором). */
    private fun httpError(label: String, code: Int, body: String?): String {
        val access = if (code == 401 || code == 403) " — нет доступа: проверьте логин/пароль и права на S900" else ""
        return "$label → HTTP $code$access ${errorText(body)}".trimEnd()
    }

    /** s900 отдаёт ошибки HTML-страницей — вырезаем теги, оставляем читаемый текст. */
    private fun errorText(raw: String?): String =
        raw.orEmpty().replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim().take(200)

    companion object {
        private const val MAX_LISTED = 5

        const val UNKNOWN_DESTINATION =
            "Долбление отправлялось до обновления приложения, и сервер той отправки неизвестен. " +
                "Если оно отправлялось на текущий сервер — подтвердите это в списке долблений и " +
                "отправьте снова. Автоматическая досылка не выполняется."
    }
}
