package com.example.llm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var agent: LlmAgent
    private lateinit var historyStorage: ChatHistoryStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        historyStorage = ChatHistoryStorage(applicationContext)
        agent = LlmAgent(historyStorage = historyStorage)

        setContent {
            var inputText by remember { mutableStateOf("") }
            var responseText by remember { mutableStateOf("Введите запрос и нажмите «Отправить»") }
            var tokenInfoText by remember { mutableStateOf("") }
            var isLoading by remember { mutableStateOf(false) }

            val scope = rememberCoroutineScope()
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp),
                    label = { Text("Ваш запрос") },
                    placeholder = { Text("Напишите сообщение...") },
                    enabled = !isLoading,
                    maxLines = 4
                )

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Токены истории: ~${agent.getHistoryTokensEstimate()} (лимит 128 000)",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            responseText = "Отправка..."
                            tokenInfoText = ""
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
