package com.example.llm

data class Message(
    val role: String,
    val content: String
)

data class ChatRequest(
    val model: String,
    val messages: List<Message>,
    val max_tokens: Int? = null,
    val temperature: Double? = null,
    val stop: List<String>? = null
)

data class Choice(
    val message: Message
)

/** Использование токенов в ответе API (если возвращается). */
data class Usage(
    val prompt_tokens: Int? = null,
    val completion_tokens: Int? = null,
    val total_tokens: Int? = null
)

data class ChatResponse(
    val choices: List<Choice>,
    val usage: Usage? = null
)
