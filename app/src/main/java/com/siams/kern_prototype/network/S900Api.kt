package com.siams.kern_prototype.network

import com.siams.kern_prototype.network.dto.AnalysisSourceResponse
import com.siams.kern_prototype.network.dto.SourceImageUpdateDto
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Part
import retrofit2.http.Path

/**
 * REST v3 s900 (§8.2). Базовый путь `/s8/api/v3/s8/` подставляет [S900Interceptor]
 * (scheme/host/port из настроек). Тела AnalysisSource — [JsonObject] с плоскими
 * гео-атрибутами (§8.5). Ответы заливки — [JsonElement] (форма id уточняется §10.2),
 * парсятся в [com.siams.kern_prototype.sync.DrillingUploader].
 */
interface S900Api {

    // Ответ create — id голой строкой (не JSON), и он нам не нужен (id знаем сами) →
    // берём сырое тело, парсинг не нужен.
    @POST("analysis/source")
    suspend fun createSource(@Body body: JsonObject): Response<ResponseBody>

    // PUT паспорта: ответ может быть пустым/строкой → сырое тело, без JSON-парсинга.
    @PUT("analysis/source/{id}")
    suspend fun updateSource(
        @Path("id") id: String,
        @Body body: JsonObject,
    ): Response<ResponseBody>

    @GET("analysis/source/{id}")
    suspend fun getSource(@Path("id") id: String): Response<AnalysisSourceResponse>

    @GET("analysis/source/{id}/fovs/size")
    suspend fun getFovSize(@Path("id") id: String): Response<Int>

    /** Залить кадр в НОВЫЙ стол (FieldOfView). */
    @Multipart
    @POST("analysis/source/{id}/image/file")
    suspend fun uploadImageNewFov(
        @Path("id") id: String,
        @Part file: MultipartBody.Part,
    ): Response<JsonElement>

    /** Залить кадр в существующий стол по индексу. */
    @Multipart
    @POST("analysis/source/{id}/fov-idx/{fovIdx}/image/file")
    suspend fun uploadImageToFov(
        @Path("id") id: String,
        @Path("fovIdx") fovIdx: Int,
        @Part file: MultipartBody.Part,
    ): Response<JsonElement>

    // PUT метаданных возвращает 200 с ПУСТЫМ телом → сырое тело, без JSON-парсинга.
    @PUT("analysis/source/{id}/image/{iid}")
    suspend fun updateImageMeta(
        @Path("id") id: String,
        @Path("iid") iid: String,
        @Body body: SourceImageUpdateDto,
    ): Response<ResponseBody>
}
