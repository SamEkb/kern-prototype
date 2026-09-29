package com.siams.kern_prototype.network

import com.siams.kern_prototype.domain.model.ServerSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface ConnectionResult {
    data class Success(val httpCode: Int) : ConnectionResult
    data object AuthFailed : ConnectionResult
    data class ServerError(val httpCode: Int) : ConnectionResult
    data class Unreachable(val message: String) : ConnectionResult
}

/**
 * «Проверить соединение» (§5.1): выполняет сессионный логин по введённым кредам
 * и пробует вызвать API. Использует изолированный CookieJar (не трогает основную
 * сессию приложения) и короткие таймауты. См. §10.3 (сессионная авторизация s900).
 */
@Singleton
class ConnectionTester @Inject constructor() {

    suspend fun test(settings: ServerSettings): ConnectionResult = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .followRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        try {
            if (settings.login.isNotBlank()) {
                val form = FormBody.Builder()
                    .add("username", settings.login)
                    .add("password", settings.password)
                    .build()
                client.newCall(Request.Builder().url(settings.loginUrl()).post(form).build())
                    .execute().close()
            }

            val probeUrl = settings.baseUrl() + "analysis/source/00000000-0000-0000-0000-000000000000"
            client.newCall(Request.Builder().url(probeUrl).get().build()).execute().use { resp ->
                when {
                    isAuthRedirect(resp) -> ConnectionResult.AuthFailed
                    resp.code >= 500 -> ConnectionResult.ServerError(resp.code)
                    else -> ConnectionResult.Success(resp.code) // 2xx/4xx/не-auth редирект — сервер доступен и авторизация прошла
                }
            }
        } catch (e: IOException) {
            ConnectionResult.Unreachable(e.message ?: "Сервер недоступен")
        }
    }

    private fun isAuthRedirect(resp: okhttp3.Response): Boolean =
        resp.code in 300..399 && resp.header("Location").orEmpty().contains("/sia/auth/")
}
