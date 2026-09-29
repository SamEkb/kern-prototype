package com.siams.kern_prototype.domain.model

/**
 * Настройки подключения к s900 (§5.1). Хранятся в DataStore, переживают перезапуск.
 */
data class ServerSettings(
    val host: String = "",
    val port: Int = 8080,
    val useHttps: Boolean = false,
    val login: String = "",
    val password: String = "",
    /** Время последней успешной загрузки конфигурации полей (§5.1), мс; 0 = нет. */
    val fieldsConfigLoadedAt: Long = 0L,
) {
    val isConfigured: Boolean get() = host.isNotBlank()

    private fun scheme() = if (useHttps) "https" else "http"

    /** Базовый URL с context-path `/s8` (§8.1). Retrofit требует завершающий `/`. */
    fun baseUrl(): String = "${scheme()}://$host:$port/s8/api/v3/s8/"

    /**
     * Эндпоинт форменного логина s900 (§10.3, подтверждено по живому серверу):
     * POST form `username`/`password` → cookie `JSESSIONID`.
     */
    fun loginUrl(): String = "${scheme()}://$host:$port/s8/sia/auth/login"
}
