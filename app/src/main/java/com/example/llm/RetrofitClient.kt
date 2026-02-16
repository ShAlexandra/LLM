package com.example.llm

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val BASE_URL = "https://openrouter.ai/api/v1/"
    private val apiKey = BuildConfig.OPENROUTER_API_KEY


    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(logging)   // ← добавляем логирование
        .addInterceptor(
            Interceptor { chain ->
                val newRequest = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $apiKey")
                    .addHeader("HTTP-Referer", "http://localhost")
                    .addHeader("X-Title", "MyAndroidApp")
                    .build()
                chain.proceed(newRequest)
            }
        )
        .build()

    val api: OpenRouterApi = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(OpenRouterApi::class.java)
}
