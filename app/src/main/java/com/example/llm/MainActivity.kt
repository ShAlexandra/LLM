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

    private val agent = LlmAgent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var inputText by remember { mutableStateOf("") }
            var responseText by remember { mutableStateOf("Введите запрос и нажмите «Отправить»") }
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

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            responseText = "Отправка..."
                            val result = agent.send(inputText)
                            responseText = when (result) {
                                is LlmAgent.AgentResult.Success -> result.content
                                is LlmAgent.AgentResult.Error -> "Ошибка: ${result.message}"
                            }
                            isLoading = false
                        }
                    },
                    enabled = !isLoading
                ) {
                    Text(if (isLoading) "Отправка..." else "Отправить")
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(16.dp))
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
