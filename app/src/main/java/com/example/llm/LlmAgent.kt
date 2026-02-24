package com.example.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Агент для запросов к LLM через API.
 * Инкапсулирует формирование запроса, вызов API и разбор ответа.
 * При передаче [ChatHistoryStorage] сохраняет историю диалога и восстанавливает её при перезапуске.
 */
class LlmAgent(
    private val modelId: String = DEFAULT_MODEL,
    private val api: DeepSeekApi = RetrofitClient.api,
    private val historyStorage: ChatHistoryStorage? = null
) {

    /**
     * Отправляет запрос пользователя в LLM и возвращает ответ или ошибку.
     * Использует сохранённую историю (если есть), чтобы продолжить диалог с контекстом.
     */
    suspend fun send(userMessage: String): AgentResult = withContext(Dispatchers.IO) {
        if (userMessage.isBlank()) {
            return@withContext AgentResult.Error("Введите сообщение")
        }
        try {
            val history = historyStorage?.loadMessages() ?: emptyList()
            val newUserMessage = Message("user", userMessage.trim())
            val messagesForRequest = history + newUserMessage

            val request = ChatRequest(
                model = modelId,
                messages = messagesForRequest,
                max_tokens = 2048
            )
            val response = api.chatCompletion(request)
            val content = response.choices.firstOrNull()?.message?.content?.trim()
                ?: return@withContext AgentResult.Error("Пустой ответ от модели")

            historyStorage?.saveMessages(messagesForRequest + Message("assistant", content))

            AgentResult.Success(content)
        } catch (e: Exception) {
            val msg = e.message ?: "Ошибка сети"
            val isTimeout = e is java.net.SocketTimeoutException ||
                msg.contains("timeout", ignoreCase = true)
            AgentResult.Error(
                if (isTimeout) "Превышено время ожидания ответа. Попробуйте короче запрос или подождите."
                else msg
            )
        }
    }

    sealed class AgentResult {
        data class Success(val content: String) : AgentResult()
        data class Error(val message: String) : AgentResult()
    }

    companion object {
        private const val DEFAULT_MODEL = "deepseek-chat"
    }
}
