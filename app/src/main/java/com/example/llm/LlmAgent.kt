package com.example.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Оценка/факт использования токенов за один обмен.
 * [requestTokens] — токены текущего запроса (оценка или из API).
 * [historyTokens] — токены истории до этого запроса (оценка).
 * [responseTokens] — токены ответа модели (из API или оценка).
 * [totalPromptTokens] — полный промпт (история + запрос), из API или сумма оценок.
 */
data class TokenInfo(
    val requestTokens: Int,
    val historyTokens: Int,
    val responseTokens: Int,
    val totalPromptTokens: Int,
    val totalTokens: Int,
    val isOverLimit: Boolean,
    val contextLimit: Int
)

/**
 * Агент для запросов к LLM через API.
 * Инкапсулирует формирование запроса, вызов API и разбор ответа.
 * При передаче [ChatHistoryStorage] сохраняет историю диалога и восстанавливает её при перезапуске.
 * Подсчитывает токены: для запроса, истории, ответа; предупреждает при переполнении контекста.
 */
class LlmAgent(
    private val modelId: String = DEFAULT_MODEL,
    private val api: DeepSeekApi = RetrofitClient.api,
    private val historyStorage: ChatHistoryStorage? = null,
    private val contextLimit: Int = DEFAULT_CONTEXT_LIMIT
) {

    /**
     * Отправляет запрос пользователя в LLM и возвращает ответ или ошибку.
     * Использует сохранённую историю (если есть). Возвращает [TokenInfo] при успехе.
     */
    suspend fun send(userMessage: String): AgentResult = withContext(Dispatchers.IO) {
        if (userMessage.isBlank()) {
            return@withContext AgentResult.Error("Введите сообщение")
        }
        try {
            val history = historyStorage?.loadMessages() ?: emptyList()
            val newUserMessage = Message("user", userMessage.trim())
            val messagesForRequest = history + newUserMessage

            val historyTokensEst = estimateTokens(history)
            val requestTokensEst = estimateTokens(listOf(newUserMessage))
            val totalPromptEst = historyTokensEst + requestTokensEst
            val overLimit = totalPromptEst > contextLimit

            if (overLimit) {
                return@withContext AgentResult.Error(
                    "Превышен лимит контекста: $totalPromptEst токенов (лимит $contextLimit). " +
                        "История: $historyTokensEst, запрос: $requestTokensEst. " +
                        "Очистите историю или сократите сообщение."
                )
            }

            val request = ChatRequest(
                model = modelId,
                messages = messagesForRequest,
                max_tokens = 2048
            )
            val response = api.chatCompletion(request)
            val content = response.choices.firstOrNull()?.message?.content?.trim()
                ?: return@withContext AgentResult.Error("Пустой ответ от модели")

            historyStorage?.saveMessages(messagesForRequest + Message("assistant", content))

            val usage = response.usage
            val promptTokens = usage?.prompt_tokens ?: totalPromptEst
            val completionTokens = usage?.completion_tokens ?: estimateTokens(content)
            val totalTokens = usage?.total_tokens ?: (promptTokens + completionTokens)

            val tokenInfo = TokenInfo(
                requestTokens = usage?.prompt_tokens?.let { it - historyTokensEst } ?: requestTokensEst,
                historyTokens = historyTokensEst,
                responseTokens = completionTokens,
                totalPromptTokens = promptTokens,
                totalTokens = totalTokens,
                isOverLimit = false,
                contextLimit = contextLimit
            )
            AgentResult.Success(content, tokenInfo)
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

    /** Оценка числа токенов по тексту (приближённо: ~4 символа на токен). */
    fun estimateTokens(messages: List<Message>): Int =
        messages.sumOf { estimateTokens(it.content) }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length + 3) / 4
    }

    /** Текущая оценка токенов истории (без нового сообщения). */
    fun getHistoryTokensEstimate(): Int {
        val history = historyStorage?.loadMessages() ?: return 0
        return estimateTokens(history)
    }

    sealed class AgentResult {
        data class Success(val content: String, val tokenInfo: TokenInfo? = null) : AgentResult()
        data class Error(val message: String) : AgentResult()
    }

    companion object {
        private const val DEFAULT_MODEL = "deepseek-chat"
        private const val DEFAULT_CONTEXT_LIMIT = 128_000
    }
}
