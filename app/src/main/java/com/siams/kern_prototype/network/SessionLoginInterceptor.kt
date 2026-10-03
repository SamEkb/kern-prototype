package com.siams.kern_prototype.network

import android.util.Log
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.domain.model.S900Destination
import com.siams.kern_prototype.domain.model.ServerSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Авторизация s900 через сессию (§10.3, подтверждено по живому серверу):
 *  1) переписывает scheme/host/port запроса на значения из настроек (baseUrl Retrofit — заглушка);
 *  2) при отсутствии сессии выполняет `POST /s8/sia/auth/login` (form username/password) и
 *     получает `JSESSIONID`; на auth-редирект, 401 или 403 — один повторный вход и один повтор.
 *
 * R12: сессии ИЗОЛИРОВАНЫ. Сессия привязана к назначению + паролю ([Session]); каждый запрос
 * один раз берёт снимок настроек и всё — вход, заголовок `Cookie`, приём `Set-Cookie` из ответа —
 * делает в СВОЕЙ сессии. Поэтому запрос не может воспользоваться cookie другой учётной записи,
 * а поздний ответ старой сессии не восстановит её cookie в новой. Cookie ведёт этот интерцептор;
 * у клиентов [CookieJar][okhttp3.CookieJar] отключён. Значения cookie в лог не пишутся.
 *
 * Вход успешен только при HTTP 2xx/3xx; любая другая ошибка входа останавливает запрос (без
 * анонимного продолжения). Положительный критерий входа сервером не согласован (Q10).
 *
 * R03: запрос, закреплённый за назначением ([S900Destination] в теге), не отправляется, если
 * настройки сменили посреди отправки.
 */
@Singleton
class SessionLoginInterceptor @Inject constructor(
    private val settingsStore: ServerSettingsStore,
    @LoginClient private val loginClient: OkHttpClient,
) : Interceptor {

    /**
     * Ключ сессии: назначение + ТОЧНОЕ значение пароля (не хеш — у разных паролей хеши совпадают).
     * Живёт только в памяти; в лог не выводится.
     */
    internal data class SessionKey(val destination: S900Destination, val password: String) {
        override fun toString() = "SessionKey(${destination.key}, пароль скрыт)"
    }

    /** Сессия одной учётной записи на одном сервере; cookie и вход — только свои. */
    internal class Session(val key: SessionKey) {
        val cookies = InMemoryCookieJar()
        val loginLock = Any()

        fun has(url: HttpUrl) = cookies.loadForRequest(url).any { it.name.equals("JSESSIONID", ignoreCase = true) }

        fun withCookies(request: Request): Request {
            val c = cookies.loadForRequest(request.url)
            val b = request.newBuilder().removeHeader("Cookie")
            if (c.isNotEmpty()) b.header("Cookie", c.joinToString("; ") { "${it.name}=${it.value}" })
            return b.build()
        }

        fun receive(response: Response) =
            cookies.saveFromResponse(response.request.url, Cookie.parseAll(response.request.url, response.headers))
    }

    @Volatile
    private var current: Session? = null

    /** Сессия для снимка настроек; смена сервера/учётной записи/пароля — новая сессия. */
    internal fun sessionFor(s: ServerSettings): Session = synchronized(this) {
        val key = SessionKey(s.destination(), s.password)
        current?.takeIf { it.key == key } ?: Session(key).also {
            if (current != null) Log.i(TAG, "Настройки сервера/учётной записи изменены → новая сессия")
            current = it
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val settings: ServerSettings = runBlocking { settingsStore.settings.first() }
        chain.request().tag(S900Destination::class.java)?.let { pinned ->
            if (pinned != settings.destination()) {
                throw IOException("Настройки сервера изменены во время отправки — отправка будет повторена")
            }
        }
        // R20: пароль не расшифровался — входить с пустым паролем нельзя (не угадывать исход входа).
        if (settings.login.isNotBlank() && settings.passwordUnavailable) {
            throw IOException("Сохранённый пароль S900 недоступен на этом устройстве — введите его заново в настройках")
        }
        val session = sessionFor(settings)
        val request = rewriteHost(chain.request(), settings)

        // Проактивный логин: сервер на API-путях НЕ редиректит на форму (§10.3), а
        // обрабатывает анонимно → «Недостаточно прав». Поэтому логинимся заранее.
        if (settings.login.isNotBlank() && !session.has(request.url)) login(settings, session, request.url, force = false)

        var response = chain.proceed(session.withCookies(request))
        session.receive(response)
        if (settings.login.isNotBlank() && needsRelogin(response)) {
            Log.i(TAG, "HTTP ${response.code} (сессия не принята) → повторный вход, один повтор запроса")
            response.close()
            login(settings, session, request.url, force = true)
            response = chain.proceed(session.withCookies(request))
            session.receive(response)
        }
        return response
    }

    private fun rewriteHost(request: Request, s: ServerSettings): Request {
        if (s.host.isBlank()) return request
        val url = request.url.newBuilder()
            .scheme(if (s.useHttps) "https" else "http")
            .host(s.host)
            .port(s.port)
            .build()
        return request.newBuilder().url(url).build()
    }

    private fun needsRelogin(response: Response): Boolean =
        response.code == 401 || response.code == 403 ||
            (response.code in 300..399 && response.header("Location").orEmpty().contains("/sia/auth/"))

    /**
     * Вход в [session] (по одному за раз внутри сессии). Ошибка — [IOException] с понятным текстом:
     * продолжать анонимно нельзя.
     */
    private fun login(s: ServerSettings, session: Session, url: HttpUrl, force: Boolean) = synchronized(session.loginLock) {
        if (!force && session.has(url)) return@synchronized // вошёл параллельный запрос этой сессии
        if (force) session.cookies.clear()
        val form = FormBody.Builder()
            .add("username", s.login)
            .add("password", s.password)
            .build()
        val request = session.withCookies(Request.Builder().url(s.loginUrl()).post(form).build())
        try {
            loginClient.newCall(request).execute().use { resp ->
                val names = resp.headers("Set-Cookie").map { it.substringBefore('=') }
                Log.i(TAG, "Вход ${s.loginUrl()} → HTTP ${resp.code}; cookie: ${names.ifEmpty { listOf("<нет>") }}")
                if (resp.code !in 200..399) {
                    throw IOException(
                        if (resp.code == 401 || resp.code == 403) "вход отклонён сервером (HTTP ${resp.code})"
                        else "ошибка входа (HTTP ${resp.code})",
                    )
                }
                // Cookie принимаются только из УСПЕШНОГО входа: сессия из отклонённого входа
                // позволила бы следующей попытке обратиться к API без авторизации.
                session.receive(resp)
            }
        } catch (e: IOException) {
            session.cookies.clear() // состояние авторизации после неудачного входа недействительно
            Log.e(TAG, "Вход не выполнен: ${e.message}")
            throw IOException("Не удалось войти на сервер: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "drilling-upload"
    }
}
