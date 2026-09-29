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
    /**
     * Сервер доступен, вход и запрос API не отклонены. Это НЕ доказательство прав учётной записи:
     * на API-путях s900 отвечает и анонимно, а положительный критерий входа не согласован (Q10).
     */
    data class Reachable(val httpCode: Int) : ConnectionResult
    data class AuthFailed(val httpCode: Int?) : ConnectionResult
    /** Запрос входа завершился HTTP-ошибкой, не означающей отказ в доступе (например, 400/404). */
    data class LoginFailed(val httpCode: Int) : ConnectionResult
    data class ServerError(val httpCode: Int) : ConnectionResult
    data class Unreachable(val message: String) : ConnectionResult
}

/**
 * «Проверить соединение» (§5.1): выполняет сессионный логин по введённым кредам
 * и пробует вызвать API. Использует изолированный CookieJar (не трогает основную
 * сессию приложения) и короткие таймауты. См. §10.3 (сессионная авторизация s900).
 *
 * R12: отказы (401/403, auth-редирект, ошибка входа) — [ConnectionResult.AuthFailed];
 * успех разделяет доступность сервера и авторизацию ([ConnectionResult.Reachable]).
 */
@Singleton
class ConnectionTester @Inject constructor() {

    internal var clientFactory: () -> OkHttpClient = {
        OkHttpClient.Builder()
            .cookieJar(InMemoryCookieJar())
            .followRedirects(false)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    suspend fun test(settings: ServerSettings): ConnectionResult = withContext(Dispatchers.IO) {
        val client = clientFactory()
        try {
            if (settings.login.isNotBlank()) {
                val form = FormBody.Builder()
                    .add("username", settings.login)
                    .add("password", settings.password)
                    .build()
                client.newCall(Request.Builder().url(settings.loginUrl()).post(form).build()).execute().use { resp ->
                    // Вход успешен только при 2xx/3xx; иначе к API не обращаемся.
                    when {
                        resp.code == 401 || resp.code == 403 -> return@withContext ConnectionResult.AuthFailed(resp.code)
                        resp.code >= 500 -> return@withContext ConnectionResult.ServerError(resp.code)
                        resp.code !in 200..399 -> return@withContext ConnectionResult.LoginFailed(resp.code)
                    }
                }
            }

            val probeUrl = settings.baseUrl() + "analysis/source/00000000-0000-0000-0000-000000000000"
            client.newCall(Request.Builder().url(probeUrl).get().build()).execute().use { resp ->
                when {
                    isAuthRedirect(resp) || resp.code == 401 || resp.code == 403 -> ConnectionResult.AuthFailed(resp.code)
                    resp.code >= 500 -> ConnectionResult.ServerError(resp.code)
                    else -> ConnectionResult.Reachable(resp.code)
                }
            }
        } catch (e: IOException) {
            ConnectionResult.Unreachable(e.message ?: "Сервер недоступен")
        }
    }

    private fun isAuthRedirect(resp: okhttp3.Response): Boolean =
        resp.code in 300..399 && resp.header("Location").orEmpty().contains("/sia/auth/")
}
