package com.siams.kern_prototype.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.siams.kern_prototype.data.settings.ServerSettingsStore
import com.siams.kern_prototype.domain.model.S900Destination
import com.siams.kern_prototype.domain.model.ServerSettings
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.Collections

/** R12/R03: сессионная авторизация, закреплённое назначение, cookie и проверка соединения. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionAndConnectionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()
    private val paths = Collections.synchronizedList(mutableListOf<String>())

    /** Ответ на вход (по имени пользователя) и на запрос API (по пути). */
    @Volatile private var loginResponse: (String) -> MockResponse = { user -> ok().addHeader("Set-Cookie", "JSESSIONID=$user; Path=/s8") }
    @Volatile private var apiResponse: (RecordedRequest) -> MockResponse = { ok() }
    /** Заголовок X-Test → Cookie, с которым пришёл API-запрос. */
    private val apiCookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun ok() = MockResponse().setResponseCode(200).setBody("{}")

    private lateinit var store: ServerSettingsStore
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path.orEmpty()
                paths += p
                if (p.startsWith("/s8/sia/auth/login")) {
                    val user = request.body.readUtf8().substringAfter("username=").substringBefore('&')
                    return loginResponse(user)
                }
                request.getHeader("X-Test")?.let { apiCookies[it] = request.getHeader("Cookie").orEmpty() }
                return apiResponse(request)
            }
        }
        server.start()
        store = ServerSettingsStore(context)
        save(login = "user")
        val loginClient = OkHttpClient.Builder().followRedirects(false).build()
        client = OkHttpClient.Builder()
            .followRedirects(false)
            .addInterceptor(SessionLoginInterceptor(store, loginClient))
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun settings(login: String, password: String = "p") =
        ServerSettings(host = server.hostName, port = server.port, login = login, password = password)
    private fun save(login: String, password: String = "p") = runBlocking { store.save(settings(login, password)) }

    /** Запрос API как у Retrofit: заглушка хоста, реальный подставит интерцептор. */
    private fun call(pinned: S900Destination? = null, id: String? = null): Int {
        val b = Request.Builder().url("http://localhost/s8/api/v3/s8/analysis/source/x")
        if (id != null) b.header("X-Test", id)
        if (pinned != null) b.tag(S900Destination::class.java, pinned)
        return client.newCall(b.build()).execute().use { it.code }
    }

    private fun logins() = paths.count { it.startsWith("/s8/sia/auth/login") }
    private fun apiCalls() = paths.count { it.startsWith("/s8/api/") }

    @Test
    fun pinnedDestinationBlocksRequestAfterSettingsChange() {
        val pinned = settings("user").destination()
        assertEquals(200, call(pinned))
        save(login = "other") // настройки сменили посреди отправки
        try { call(pinned); fail("запрос ушёл бы под другим назначением") } catch (e: IOException) {
            assertTrue(e.message!!.contains("изменены во время отправки"))
        }
        assertEquals(1, apiCalls())
    }

    @Test
    fun loginOnceThenSessionReused() {
        call(); call()
        assertEquals(1, logins())
        assertEquals(2, apiCalls())
    }

    @Test
    fun unauthorizedTriggersSingleReloginAndSingleRetry() {
        apiResponse = { MockResponse().setResponseCode(401) }
        assertEquals(401, call())
        assertEquals("вход + повторный вход", 2, logins())
        assertEquals("запрос + один повтор", 2, apiCalls())
    }

    @Test
    fun forbiddenTriggersSingleRelogin() {
        apiResponse = { MockResponse().setResponseCode(403) }
        assertEquals(403, call())
        assertEquals(2, logins())
    }

    @Test
    fun loginFailureIsNotSilentlyAnonymous() {
        loginResponse = { MockResponse().setResponseCode(500) }
        try { call(); fail() } catch (e: IOException) {
            assertTrue(e.message!!.contains("Не удалось войти"))
        }
        assertEquals("анонимный запрос не отправлен", 0, apiCalls())
    }

    @Test
    fun accountChangeResetsSession() {
        call()
        save(login = "other")
        call()
        assertEquals("новая учётная запись — новый вход", 2, logins())
    }

    @Test
    fun cookiesOfRejectedLoginDoNotAuthorizeNextAttempt() {
        for (code in listOf(401, 500)) {
            loginResponse = { MockResponse().setResponseCode(code).addHeader("Set-Cookie", "JSESSIONID=rejected; Path=/s8") }
            val before = logins()
            repeat(2) { attempt ->
                try { call(); fail("HTTP $code, попытка ${attempt + 1}: запрос прошёл без входа") } catch (_: IOException) {}
            }
            assertEquals("HTTP $code: каждая попытка снова входит", before + 2, logins())
        }
        assertEquals("к API без авторизации не обращались", 0, apiCalls())
    }

    @Test
    fun passwordChangeWithEqualHashCodeStartsNewSession() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        save(login = "user", password = "Aa")
        call()
        save(login = "user", password = "BB")
        call()
        assertEquals("новый пароль — новый вход", 2, logins())
    }

    @Test
    fun loginHttpErrorsStopTheRequest() {
        for (code in listOf(400, 404)) {
            loginResponse = { MockResponse().setResponseCode(code) }
            try { call(); fail("HTTP $code входа пропущен") } catch (e: IOException) {
                assertTrue(e.message!!.contains("Не удалось войти"))
            }
        }
        assertEquals("к API не обращались", 0, apiCalls())
    }

    @Test
    fun parallelAccountChangeDoesNotLeakSessionIntoSlowRequest() {
        // Вход «user» медленный; пока он идёт, учётную запись меняют и другой запрос входит как «other».
        loginResponse = { user ->
            if (user == "user") Thread.sleep(500)
            ok().addHeader("Set-Cookie", "JSESSIONID=$user; Path=/s8")
        }
        val slow = Thread { call(id = "A") }.apply { start() }
        Thread.sleep(150)          // A прошёл проверку и ждёт вход
        save(login = "other")
        call(id = "B")
        slow.join(5_000)
        assertEquals("JSESSIONID=other", apiCookies["B"])
        assertEquals("запрос A не взял cookie чужой сессии", "JSESSIONID=user", apiCookies["A"])
    }

    @Test
    fun lateResponseDoesNotRestoreOldCookiesIntoNewSession() {
        // Ответ API для A приходит поздно и несёт cookie старой учётной записи.
        apiResponse = { r ->
            if (r.getHeader("X-Test") == "A") {
                Thread.sleep(500)
                ok().addHeader("Set-Cookie", "JSESSIONID=stale-user; Path=/s8")
            } else ok()
        }
        val slow = Thread { call(id = "A") }.apply { start() }
        Thread.sleep(150)
        save(login = "other")
        call(id = "B")
        slow.join(5_000)           // поздний ответ A с Set-Cookie получен
        call(id = "C")
        assertEquals("JSESSIONID=other", apiCookies["C"])
    }

    @Test
    fun connectionTestStopsOnLoginHttpError() {
        loginResponse = { MockResponse().setResponseCode(404) }
        assertEquals(ConnectionResult.LoginFailed(404), test())
        loginResponse = { MockResponse().setResponseCode(400) }
        assertEquals(ConnectionResult.LoginFailed(400), test())
        assertEquals("проверка API не выполнялась", 0, apiCalls())
    }

    @Test
    fun cookieJarHonoursExpiryPathAndSecure() {
        val url = "http://h.example/s8/api/x".toHttpUrl()
        val jar = InMemoryCookieJar()
        jar.saveFromResponse(
            url,
            listOf(
                Cookie.parse(url, "A=1; Path=/s8")!!,
                Cookie.parse(url, "B=2; Path=/other")!!,
                Cookie.parse(url, "C=3; Path=/s8; Max-Age=0")!!,
                Cookie.parse("https://h.example/".toHttpUrl(), "D=4; Path=/; Secure")!!,
            ),
        )
        assertEquals(listOf("A"), jar.loadForRequest(url).map { it.name })
        assertEquals(listOf("A", "D"), jar.loadForRequest("https://h.example/s8/y".toHttpUrl()).map { it.name }.sorted())
        assertEquals("другой хост", emptyList<String>(), jar.loadForRequest("http://other.example/s8/".toHttpUrl()).map { it.name })
    }

    // ---- «Проверить соединение» ----

    private fun test(): ConnectionResult = runBlocking {
        ConnectionTester().apply {
            clientFactory = { OkHttpClient.Builder().cookieJar(InMemoryCookieJar()).followRedirects(false).build() }
        }.test(settings("user"))
    }

    @Test
    fun connectionTestReportsForbiddenAsAuthFailure() {
        apiResponse = { MockResponse().setResponseCode(403) }
        assertEquals(ConnectionResult.AuthFailed(403), test())
        apiResponse = { MockResponse().setResponseCode(401) }
        assertEquals(ConnectionResult.AuthFailed(401), test())
    }

    @Test
    fun connectionTestReportsRejectedLogin() {
        loginResponse = { MockResponse().setResponseCode(401) }
        assertEquals(ConnectionResult.AuthFailed(401), test())
        loginResponse = { MockResponse().setResponseCode(500) }
        assertEquals(ConnectionResult.ServerError(500), test())
    }

    @Test
    fun connectionTestReachableIsNotClaimedAsAuthorized() {
        apiResponse = { MockResponse().setResponseCode(404) }
        assertEquals(ConnectionResult.Reachable(404), test())
    }
}
