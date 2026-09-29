package com.siams.kern_prototype.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Простой in-memory CookieJar (по хосту, по имени куки). Нужен для сессионной
 * авторизации s900 — хранит `JSESSIONID` между логином и запросами API (§10.3).
 * JSESSIONID — session-cookie без expiry, поэтому фильтрация по времени не нужна.
 */
@Singleton
class InMemoryCookieJar @Inject constructor() : CookieJar {

    private val store = mutableMapOf<String, MutableMap<String, Cookie>>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val forHost = store.getOrPut(url.host) { mutableMapOf() }
        cookies.forEach { forHost[it.name] = it }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store[url.host]?.values?.toList() ?: emptyList()

    @Synchronized
    fun clear(host: String? = null) {
        if (host == null) store.clear() else store.remove(host)
    }
}
