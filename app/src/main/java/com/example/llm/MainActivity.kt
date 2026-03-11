package com.example.llm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var agent: LlmAgent
    private lateinit var historyStorage: ChatHistoryStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyStorage = ChatHistoryStorage(applicationContext)
        agent = LlmAgent(historyStorage = historyStorage, strategyDefault = ContextStrategy.SLIDING_WINDOW)

        setContent {
            var inputText by remember { mutableStateOf("") }
            var responseText by remember { mutableStateOf("Введите запрос и нажмите «Отправить»") }
            var tokenInfoText by remember { mutableStateOf("") }
            var isLoading by remember { mutableStateOf(false) }
            var selectedStrategy by remember { mutableStateOf(agent.contextStrategy) }
            var branchNames by remember { mutableStateOf(agent.getBranchNames()) }
            var currentBranch by remember { mutableStateOf(agent.getCurrentBranchName()) }
            var newBranchName by remember { mutableStateOf("") }
            var uiRefreshTrigger by remember { mutableStateOf(0) }
            var workingCount by remember { mutableStateOf(0) }
            var longTermCount by remember { mutableStateOf(0) }
            var memorySaveToWorking by remember { mutableStateOf(true) }

            val scope = rememberCoroutineScope()
            val scrollState = rememberScrollState()

            fun refreshBranchState() {
                branchNames = agent.getBranchNames()
                currentBranch = agent.getCurrentBranchName()
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Text("Стратегия контекста", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        ContextStrategy.SLIDING_WINDOW to "Sliding",
                        ContextStrategy.STICKY_FACTS to "Facts",
                        ContextStrategy.BRANCHING to "Ветки",
                        ContextStrategy.MEMORY_LAYERS to "Память"
                    ).forEach { (strategy, label) ->
                        FilterChip(
                            selected = selectedStrategy == strategy,
                            onClick = {
                                selectedStrategy = strategy
                                agent.contextStrategy = strategy
                                refreshBranchState()
                                if (strategy == ContextStrategy.MEMORY_LAYERS) {
                                    workingCount = agent.getWorkingMemory().size
                                    longTermCount = agent.getLongTermMemory().size
                                }
                            },
                            label = { Text(label) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }

                if (selectedStrategy == ContextStrategy.BRANCHING) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Ветка: $currentBranch", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        branchNames.forEach { name ->
                            FilterChip(
                                selected = currentBranch == name,
                                onClick = {
                                    agent.switchBranch(name)
                                    currentBranch = agent.getCurrentBranchName()
                                },
                                label = { Text(name) },
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        OutlinedTextField(
                            value = newBranchName,
                            onValueChange = { newBranchName = it },
                            modifier = Modifier.width(120.dp).padding(end = 4.dp),
                            placeholder = { Text("Имя ветки") },
                            singleLine = true
                        )
                        TextButton(onClick = {
                            if (newBranchName.isNotBlank() && agent.createBranch(newBranchName.trim())) {
                                newBranchName = ""
                                refreshBranchState()
                                currentBranch = agent.getCurrentBranchName()
                            }
                        }) { Text("Новая ветка") }
                    }
                }

                if (selectedStrategy == ContextStrategy.MEMORY_LAYERS) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Рабочая: $workingCount записей. Долговременная: $longTermCount записей.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text("Куда сохранять:", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = memorySaveToWorking,
                            onClick = { memorySaveToWorking = true },
                            label = { Text("В рабочую") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                        FilterChip(
                            selected = !memorySaveToWorking,
                            onClick = { memorySaveToWorking = false },
                            label = { Text("В долговременную") },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                    TextButton(onClick = {
                        agent.clearWorkingMemory()
                        workingCount = 0
                    }) { Text("Очистить рабочую") }
                }

                Spacer(modifier = Modifier.height(8.dp))
                key(uiRefreshTrigger) {
                    Text(
                        text = "Токены истории: ~${agent.getHistoryTokensEstimate()} (лимит 128 000)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    label = { Text("Сообщение") },
                    placeholder = { Text("Напишите сообщение или факт для памяти...") },
                    enabled = !isLoading,
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = {
                            scope.launch {
                                isLoading = true
                                responseText = "Отправка..."
                                tokenInfoText = ""
                                val textToSend = inputText.trim()
                                if (selectedStrategy == ContextStrategy.MEMORY_LAYERS && textToSend.isNotEmpty()) {
                                    val key = "record_${System.currentTimeMillis()}"
                                    if (memorySaveToWorking) {
                                        agent.addToWorkingMemory(key, textToSend)
                                        workingCount = agent.getWorkingMemory().size
                                    } else {
                                        agent.addToLongTermMemory(key, textToSend)
                                        longTermCount = agent.getLongTermMemory().size
                                    }
                                }
                                val result = agent.send(inputText)
                                when (result) {
                                    is LlmAgent.AgentResult.Success -> {
                                        responseText = result.content
                                        result.tokenInfo?.let { t ->
                                            tokenInfoText = buildString {
                                                append("Токены: запрос ${t.requestTokens}, ")
                                                append("история ${t.historyTokens}, ")
                                                append("ответ ${t.responseTokens}. ")
                                                append("Всего промпт: ${t.totalPromptTokens}, всего: ${t.totalTokens}. ")
                                                append("Лимит: ${t.contextLimit}")
                                            }
                                        }
                                        refreshBranchState()
                                        uiRefreshTrigger++
                                        inputText = ""
                                    }
                                    is LlmAgent.AgentResult.Error -> {
                                        responseText = "Ошибка: ${result.message}"
                                    }
                                }
                                isLoading = false
                            }
                    },
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "Отправка..." else "Отправить")
                }

                TextButton(onClick = {
                    historyStorage.clearHistory()
                    responseText = "История очищена. Токены истории: 0."
                    tokenInfoText = ""
                    inputText = ""
                    refreshBranchState()
                    uiRefreshTrigger++
                    if (selectedStrategy == ContextStrategy.MEMORY_LAYERS) {
                        workingCount = 0
                    }
                }) {
                    Text("Очистить историю")
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                }

                if (tokenInfoText.isNotEmpty()) {
                    Text(
                        text = tokenInfoText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = responseText,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
