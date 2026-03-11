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
 * Поддерживает стратегии контекста: Sliding Window, Sticky Facts, Branching, Memory Layers.
 * Memory Layers: краткосрочная (диалог) + рабочая (задача) + долговременная (профиль, знания).
 */
class LlmAgent(
    private val modelId: String = DEFAULT_MODEL,
    private val api: DeepSeekApi = RetrofitClient.api,
    private val historyStorage: ChatHistoryStorage? = null,
    private val contextLimit: Int = DEFAULT_CONTEXT_LIMIT,
    strategyDefault: ContextStrategy = ContextStrategy.SLIDING_WINDOW,
    private val slidingWindowSize: Int = SLIDING_WINDOW_DEFAULT,
    private val factsWindowSize: Int = FACTS_WINDOW_DEFAULT,
    private val memoryShortTermSize: Int = MEMORY_SHORT_TERM_DEFAULT
) {

    var contextStrategy: ContextStrategy = strategyDefault

    /**
     * Отправляет запрос пользователя в LLM. Контекст формируется по выбранной стратегии.
     */
    suspend fun send(userMessage: String): AgentResult = withContext(Dispatchers.IO) {
        if (userMessage.isBlank()) {
            return@withContext AgentResult.Error("Введите сообщение")
        }
        try {
            when (contextStrategy) {
                ContextStrategy.SLIDING_WINDOW -> sendSlidingWindow(userMessage)
                ContextStrategy.STICKY_FACTS -> sendStickyFacts(userMessage)
                ContextStrategy.BRANCHING -> sendBranching(userMessage)
                ContextStrategy.MEMORY_LAYERS -> sendMemoryLayers(userMessage)
            }
        } catch (e: Exception) {
            val msg = e.message ?: "Ошибка сети"
            val isTimeout = e is java.net.SocketTimeoutException || msg.contains("timeout", ignoreCase = true)
            AgentResult.Error(
                if (isTimeout) "Превышено время ожидания ответа. Попробуйте короче запрос или подождите."
                else msg
            )
        }
    }

    private fun getProfileMessage(): Message? =
        historyStorage?.getActiveProfile()?.takeIf { !it.isEmpty() }?.let {
            Message("system", it.toSystemMessage())
        }

    private suspend fun sendSlidingWindow(userMessage: String): AgentResult {
        val history = historyStorage?.loadSliding()?.takeLast(slidingWindowSize) ?: emptyList()
        val newUser = Message("user", userMessage.trim())
        val messagesForRequest = buildList {
            getProfileMessage()?.let { add(it) }
            addAll(history)
            add(newUser)
        }
        val historyTokensEst = estimateTokens(messagesForRequest) - estimateTokens(listOf(newUser))
        val requestTokensEst = estimateTokens(listOf(newUser))
        if (historyTokensEst + requestTokensEst > contextLimit) {
            return AgentResult.Error("Превышен лимит контекста. Очистите историю.")
        }
        val response = api.chatCompletion(ChatRequest(model = modelId, messages = messagesForRequest, max_tokens = 2048))
        val content = response.choices.firstOrNull()?.message?.content?.trim()
            ?: return AgentResult.Error("Пустой ответ от модели")
        val fullList = history + newUser + Message("assistant", content)
        historyStorage?.saveSliding(fullList.takeLast(slidingWindowSize))
        return buildSuccess(content, response, historyTokensEst, requestTokensEst)
    }

    private suspend fun sendStickyFacts(userMessage: String): AgentResult {
        val state = historyStorage?.loadFacts() ?: FactsState("", emptyList())
        val recent = state.messages.takeLast(factsWindowSize)
        val newUser = Message("user", userMessage.trim())
        val messagesForRequest = buildList {
            getProfileMessage()?.let { add(it) }
            if (state.facts.isNotBlank()) {
                add(Message("system", "Важные факты из диалога (цели, ограничения, предпочтения, решения):\n${state.facts}"))
            }
            addAll(recent)
            add(newUser)
        }
        val requestTokensEst = estimateTokens(listOf(newUser))
        val historyTokensEst = estimateTokens(messagesForRequest) - requestTokensEst
        if (historyTokensEst + requestTokensEst > contextLimit) {
            return AgentResult.Error("Превышен лимит контекста. Очистите историю.")
        }
        val response = api.chatCompletion(ChatRequest(model = modelId, messages = messagesForRequest, max_tokens = 2048))
        val content = response.choices.firstOrNull()?.message?.content?.trim()
            ?: return AgentResult.Error("Пустой ответ от модели")
        val newFacts = extractFacts(state.facts, newUser.content, content)
        val fullList = state.messages + newUser + Message("assistant", content)
        historyStorage?.saveFacts(FactsState(newFacts, fullList.takeLast(factsWindowSize)))
        return buildSuccess(content, response, historyTokensEst, requestTokensEst)
    }

    private suspend fun extractFacts(existingFacts: String, userText: String, assistantText: String): String {
        val prompt = buildString {
            if (existingFacts.isNotBlank()) append("Текущие факты:\n$existingFacts\n\n")
            append("Новый обмен:\nuser: $userText\nassistant: $assistantText\n\n")
            append("Извлеки и обнови ключевые факты (цель, ограничения, предпочтения, решения). Формат: ключ: значение, по одному на строку. Только факты, без лишнего текста.")
        }
        val req = ChatRequest(
            model = modelId,
            messages = listOf(Message("user", prompt)),
            max_tokens = 512
        )
        val res = api.chatCompletion(req)
        val extracted = res.choices.firstOrNull()?.message?.content?.trim() ?: return existingFacts
        return if (existingFacts.isBlank()) extracted else "$existingFacts\n$extracted"
    }

    private suspend fun sendBranching(userMessage: String): AgentResult {
        var state = historyStorage?.loadBranching() ?: BranchingState("main", mapOf("main" to emptyList()))
        val currentMessages = state.currentMessages()
        val newUser = Message("user", userMessage.trim())
        val messagesForRequest = buildList {
            getProfileMessage()?.let { add(it) }
            addAll(currentMessages)
            add(newUser)
        }
        val requestTokensEst = estimateTokens(listOf(newUser))
        val historyTokensEst = estimateTokens(messagesForRequest) - requestTokensEst
        if (historyTokensEst + requestTokensEst > contextLimit) {
            return AgentResult.Error("Превышен лимит контекста. Очистите историю или смените ветку.")
        }
        val response = api.chatCompletion(ChatRequest(model = modelId, messages = messagesForRequest, max_tokens = 2048))
        val content = response.choices.firstOrNull()?.message?.content?.trim()
            ?: return AgentResult.Error("Пустой ответ от модели")
        val updated = state.currentMessages() + newUser + Message("assistant", content)
        val newBranches = state.branches + (state.currentBranchName to updated)
        state = BranchingState(state.currentBranchName, newBranches)
        historyStorage?.saveBranching(state)
        return buildSuccess(content, response, historyTokensEst, requestTokensEst)
    }

    private suspend fun sendMemoryLayers(userMessage: String): AgentResult {
        val snapshot = historyStorage?.loadMemorySnapshot(memoryShortTermSize) ?: MemorySnapshot(emptyList(), emptyList(), emptyList())
        val newUser = Message("user", userMessage.trim())
        val contextMessages = snapshot.toContextMessages()
        val messagesForRequest = buildList {
            getProfileMessage()?.let { add(it) }
            addAll(contextMessages)
            add(newUser)
        }
        val requestTokensEst = estimateTokens(listOf(newUser))
        val historyTokensEst = estimateTokens(messagesForRequest) - requestTokensEst
        if (historyTokensEst + requestTokensEst > contextLimit) {
            return AgentResult.Error("Превышен лимит контекста. Очистите историю или рабочую память.")
        }
        val response = api.chatCompletion(ChatRequest(model = modelId, messages = messagesForRequest, max_tokens = 2048))
        val content = response.choices.firstOrNull()?.message?.content?.trim()
            ?: return AgentResult.Error("Пустой ответ от модели")
        val newShortTerm = (snapshot.shortTerm + newUser + Message("assistant", content)).takeLast(memoryShortTermSize)
        historyStorage?.saveShortTermMemory(newShortTerm)
        return buildSuccess(content, response, historyTokensEst, requestTokensEst)
    }

    private fun buildSuccess(
        content: String,
        response: ChatResponse,
        historyTokensEst: Int,
        requestTokensEst: Int
    ): AgentResult {
        val usage = response.usage
        val promptTokens = usage?.prompt_tokens ?: (historyTokensEst + requestTokensEst)
        val completionTokens = usage?.completion_tokens ?: estimateTokens(content)
        val totalTokens = usage?.total_tokens ?: (promptTokens + completionTokens)
        val tokenInfo = TokenInfo(
            requestTokens = requestTokensEst,
            historyTokens = historyTokensEst,
            responseTokens = completionTokens,
            totalPromptTokens = promptTokens,
            totalTokens = totalTokens,
            isOverLimit = false,
            contextLimit = contextLimit
        )
        return AgentResult.Success(content, tokenInfo)
    }

    fun estimateTokens(messages: List<Message>): Int = messages.sumOf { estimateTokens(it.content) }
    fun estimateTokens(text: String): Int = if (text.isBlank()) 0 else (text.length + 3) / 4

    fun getHistoryTokensEstimate(): Int {
        val storage = historyStorage ?: return 0
        val profileMsg = getProfileMessage()
        val profileTokens = if (profileMsg != null) estimateTokens(profileMsg.content) else 0
        val base = when (contextStrategy) {
            ContextStrategy.SLIDING_WINDOW -> estimateTokens(storage.loadSliding())
            ContextStrategy.STICKY_FACTS -> {
                val s = storage.loadFacts()
                estimateTokens(if (s.facts.isNotBlank()) listOf(Message("system", s.facts)) + s.messages else s.messages)
            }
            ContextStrategy.BRANCHING -> estimateTokens(storage.loadBranching().currentMessages())
            ContextStrategy.MEMORY_LAYERS -> estimateTokens(storage.loadMemorySnapshot(memoryShortTermSize).toContextMessages())
        }
        return base + profileTokens
    }

    // --- Memory Layers: явное сохранение в рабочую и долговременную память ---
    fun addToWorkingMemory(key: String, value: String) {
        if (historyStorage == null) return
        val list = (historyStorage.loadWorkingMemory()) + MemoryEntry(key.trim(), value.trim())
        historyStorage.saveWorkingMemory(list)
    }

    fun addToLongTermMemory(key: String, value: String) {
        if (historyStorage == null) return
        val list = (historyStorage.loadLongTermMemory()) + MemoryEntry(key.trim(), value.trim())
        historyStorage.saveLongTermMemory(list)
    }

    fun getWorkingMemory(): List<MemoryEntry> = historyStorage?.loadWorkingMemory() ?: emptyList()
    fun getLongTermMemory(): List<MemoryEntry> = historyStorage?.loadLongTermMemory() ?: emptyList()
    fun clearWorkingMemory() { historyStorage?.saveWorkingMemory(emptyList()) }

    // --- Branching: создание ветки и переключение ---
    fun createBranch(newBranchName: String): Boolean {
        val state = historyStorage?.loadBranching() ?: return false
        if (state.branches.containsKey(newBranchName)) return false
        val copy = state.currentMessages()
        historyStorage.saveBranching(BranchingState(newBranchName, state.branches + (newBranchName to copy)))
        return true
    }

    fun switchBranch(branchName: String): Boolean {
        val state = historyStorage?.loadBranching() ?: return false
        if (!state.branches.containsKey(branchName)) return false
        historyStorage.saveBranching(state.copy(currentBranchName = branchName))
        return true
    }

    fun getBranchNames(): List<String> = historyStorage?.loadBranching()?.branches?.keys?.toList() ?: listOf("main")
    fun getCurrentBranchName(): String = historyStorage?.loadBranching()?.currentBranchName ?: "main"

    sealed class AgentResult {
        data class Success(val content: String, val tokenInfo: TokenInfo? = null) : AgentResult()
        data class Error(val message: String) : AgentResult()
    }

    companion object {
        private const val DEFAULT_MODEL = "deepseek-chat"
        private const val DEFAULT_CONTEXT_LIMIT = 128_000
        private const val SLIDING_WINDOW_DEFAULT = 20
        private const val FACTS_WINDOW_DEFAULT = 20
        private const val MEMORY_SHORT_TERM_DEFAULT = 20
    }
}
