package com.example.llm

import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

interface DeepSeekApi {

    @Headers("Content-Type: application/json")
    @POST("chat/completions")
    suspend fun chatCompletion(
        @Body request: ChatRequest
    ): ChatResponse
}
