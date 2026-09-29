package com.siams.kern_prototype.network

import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.domain.model.ServerSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Авторизация s900 через сессию (§10.3, подтверждено по живому серверу):
 *  1) переписывает scheme/host/port запроса на значения из настроек (baseUrl Retrofit — заглушка);
 *  2) если сервер редиректит на форму логина (`…/sia/auth/…`), выполняет
 *     `POST /s8/sia/auth/login` (form username/password), ловит `JSESSIONID` в общий
 *     [InMemoryCookieJar] и повторяет исходный запрос уже с сессией.
 *
 * Клиент настроен с `followRedirects=false`, чтобы видеть 302 и реагировать логином.
 * Логин выполняется отдельным [LoginClient] (тот же CookieJar, без этого интерцептора).
 */
@Singleton
class SessionLoginInterceptor @Inject constructor(
    private val settingsStore: ServerSettingsStore,
    private val cookieJar: InMemoryCookieJar,
    @LoginClient private val loginClient: OkHttpClient,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val settings: ServerSettings = runBlocking { settingsStore.settings.first() }
        val request = rewriteHost(chain.request(), settings)

        // Проактивный логин: сервер на API-путях НЕ редиректит на форму (§10.3), а
        // обрабатывает анонимно → «Недостаточно прав». Поэтому логинимся заранее,
        // если сессии (JSESSIONID) ещё нет.
        if (settings.login.isNotBlank() && !hasSession(request.url.host)) {
            android.util.Log.i(TAG, "Нет сессии для ${request.url.host} → логинимся заранее")
            runCatching { login(settings) }
                .onFailure { android.util.Log.e(TAG, "Логин упал: ${it.message}") }
        }

        var response = chain.proceed(request)
        if (isAuthRedirect(response) && settings.login.isNotBlank()) {
            android.util.Log.i(TAG, "Получен auth-редирект → повторный логин")
            response.close()
            runCatching { login(settings) }
            response = chain.proceed(request) // CookieJar теперь несёт авторизованный JSESSIONID
        }
        return response
    }

    private fun hasSession(host: String): Boolean =
        cookieJar.loadForRequest(
            okhttp3.HttpUrl.Builder().scheme("http").host(host).build(),
        ).any { it.name.equals("JSESSIONID", ignoreCase = true) }

    private fun rewriteHost(request: Request, s: ServerSettings): Request {
        if (s.host.isBlank()) return request
        val url = request.url.newBuilder()
            .scheme(if (s.useHttps) "https" else "http")
            .host(s.host)
            .port(s.port)
            .build()
        return request.newBuilder().url(url).build()
    }

    private fun isAuthRedirect(response: Response): Boolean {
        if (response.code !in 300..399) return false
        val location = response.header("Location").orEmpty()
        return location.contains("/sia/auth/")
    }

    private fun login(s: ServerSettings) {
        val form = FormBody.Builder()
            .add("username", s.login)
            .add("password", s.password)
            .build()
        val request = Request.Builder().url(s.loginUrl()).post(form).build()
        loginClient.newCall(request).execute().use { resp ->
            val setCookie = resp.headers("Set-Cookie").joinToString("; ")
            android.util.Log.i(
                TAG,
                "Логин ${s.loginUrl()} → HTTP ${resp.code}; Set-Cookie: ${setCookie.ifBlank { "<нет>" }}",
            )
        }
    }

    companion object {
        private const val TAG = "drilling-upload"
    }
}
