package com.siams.kern_prototype.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * In-memory хранилище cookie одной сессии s900 (§10.3): хранит `JSESSIONID` между логином и
 * запросами API. Экземпляр — на сессию ([SessionLoginInterceptor.Session]) или на проверку соединения. R12: соблюдает правила cookie — домен/хост, путь, Secure и срок
 * действия ([Cookie.matches], `expiresAt`); одноимённые cookie разных доменов/путей не смешиваются.
 */
class InMemoryCookieJar : CookieJar {

    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) {
            this.cookies.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
            if (c.expiresAt > System.currentTimeMillis()) this.cookies += c // истёкшая = удаление
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        cookies.removeAll { it.expiresAt <= now }
        return cookies.filter { it.matches(url) }
    }

    /** Сбросить все сессии (смена сервера/учётной записи, устаревшая сессия). */
    @Synchronized
    fun clear() {
        cookies.clear()
    }
}
