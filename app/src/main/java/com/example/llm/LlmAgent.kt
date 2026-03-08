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
 * При передаче [ChatHistoryStorage] сохраняет историю и восстанавливает её при перезапуске.
 * Поддерживает сжатие контекста: последние N сообщений хранятся целиком, остальное — в виде summary.
 * Подсчитывает токены и предупреждает при переполнении контекста.
 */
class LlmAgent(
    private val modelId: String = DEFAULT_MODEL,
    private val api: DeepSeekApi = RetrofitClient.api,
    private val historyStorage: ChatHistoryStorage? = null,
    private val contextLimit: Int = DEFAULT_CONTEXT_LIMIT,
    compressionEnabledDefault: Boolean = true,
    private val maxRecentMessages: Int = MAX_RECENT_DEFAULT,
    private val compressEvery: Int = COMPRESS_EVERY_DEFAULT
) {

    var compressionEnabled: Boolean = compressionEnabledDefault

    /**
     * Отправляет запрос пользователя в LLM и возвращает ответ или ошибку.
     * Использует сохранённую историю (summary + последние сообщения). При включённом сжатии
     * раз в [compressEvery] сообщений старые реплики сворачиваются в summary. Возвращает [TokenInfo] при успехе.
     */
    suspend fun send(userMessage: String): AgentResult = withContext(Dispatchers.IO) {
        if (userMessage.isBlank()) {
            return@withContext AgentResult.Error("Введите сообщение")
        }
        try {
            val snapshot = historyStorage?.loadSnapshot() ?: HistorySnapshot("", emptyList())
            val historyMessages = if (compressionEnabled) snapshot.toListForEstimate() else snapshot.messages
            val newUserMessage = Message("user", userMessage.trim())
            val messagesForRequest = historyMessages + newUserMessage

            val historyTokensEst = estimateTokens(historyMessages)
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

            val fullList = snapshot.messages + newUserMessage + Message("assistant", content)
            if (compressionEnabled && fullList.size > maxRecentMessages) {
                val toCompress = fullList.take(compressEvery)
                val remaining = fullList.drop(compressEvery)
                val newSummaryPart = summarizeMessages(toCompress)
                val newSummary = if (snapshot.summary.isBlank()) newSummaryPart
                    else snapshot.summary + "\n\n---\n\n" + newSummaryPart
                historyStorage?.saveSnapshot(newSummary, remaining)
            } else {
                historyStorage?.saveSnapshot(if (compressionEnabled) snapshot.summary else "", fullList)
            }

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

    /** Вызывает модель для краткого содержания списка сообщений (на русском). */
    private suspend fun summarizeMessages(messages: List<Message>): String {
        if (messages.isEmpty()) return ""
        val text = messages.joinToString("\n") { "${it.role}: ${it.content}" }
        val req = ChatRequest(
            model = modelId,
            messages = listOf(
                Message("system", "Кратко перескажи диалог на русском в 2–4 предложениях, сохрани ключевые факты и решения."),
                Message("user", text)
            ),
            max_tokens = 512
        )
        val res = api.chatCompletion(req)
        return res.choices.firstOrNull()?.message?.content?.trim() ?: ""
    }

    /** Оценка числа токенов по тексту (приближённо: ~4 символа на токен). */
    fun estimateTokens(messages: List<Message>): Int =
        messages.sumOf { estimateTokens(it.content) }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length + 3) / 4
    }

    /** Текущая оценка токенов истории (summary + последние сообщения, если сжатие вкл). */
    fun getHistoryTokensEstimate(): Int {
        val snapshot = historyStorage?.loadSnapshot() ?: return 0
        val list = if (compressionEnabled) snapshot.toListForEstimate() else snapshot.messages
        return estimateTokens(list)
    }

    sealed class AgentResult {
        data class Success(val content: String, val tokenInfo: TokenInfo? = null) : AgentResult()
        data class Error(val message: String) : AgentResult()
    }

    companion object {
        private const val DEFAULT_MODEL = "deepseek-chat"
        private const val DEFAULT_CONTEXT_LIMIT = 128_000
        private const val MAX_RECENT_DEFAULT = 20
        private const val COMPRESS_EVERY_DEFAULT = 10
    }
}
