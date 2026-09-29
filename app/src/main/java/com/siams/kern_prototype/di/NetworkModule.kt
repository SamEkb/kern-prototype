package com.siams.kern_prototype.di

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import com.siams.kern_prototype.network.InMemoryCookieJar
import com.siams.kern_prototype.network.LoginClient
import com.siams.kern_prototype.network.S900Api
import com.siams.kern_prototype.network.SessionLoginInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Заглушка — реальные scheme/host/port подставляет [SessionLoginInterceptor]. */
    private const val PLACEHOLDER_BASE_URL = "http://localhost/s8/api/v3/s8/"

    private fun logging() = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    /** Клиент только для запроса логина: общий CookieJar, без session-интерцептора. */
    @Provides
    @Singleton
    @LoginClient
    fun provideLoginClient(cookieJar: InMemoryCookieJar): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .followRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging())
            .build()

    @Provides
    @Singleton
    fun provideOkHttp(
        cookieJar: InMemoryCookieJar,
        sessionInterceptor: SessionLoginInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            // followRedirects=false — чтобы видеть 302 на форму логина и авторизоваться (§10.3).
            .followRedirects(false)
            .addInterceptor(sessionInterceptor)
            .addInterceptor(logging())
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(PLACEHOLDER_BASE_URL)
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    @Provides
    @Singleton
    fun provideS900Api(retrofit: Retrofit): S900Api = retrofit.create(S900Api::class.java)
}
