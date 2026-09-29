package com.siams.kern_prototype.network

import javax.inject.Qualifier

/** Отдельный OkHttpClient для запроса логина (без session-интерцептора, чтобы не зациклиться). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LoginClient
