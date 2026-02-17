package com.example.llm   // ← проверь свой package

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {

            var responseText by remember { mutableStateOf("Нажмите кнопку") }
            var isLoading by remember { mutableStateOf(false) }

            val scope = rememberCoroutineScope()
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {

                Button(
                    onClick = {
                        scope.launch {

                            isLoading = true
                            responseText = "Запрос отправлен..."

                            try {

                                val baseQuestion = "Объясни чёрные дыры"

                                // 🔹 1. Без ограничений
                                val requestNoLimits = ChatRequest(
                                    model = "meta-llama/llama-3.1-8b-instruct",
                                    messages = listOf(
                                        Message("user", baseQuestion)
                                    )
                                )

                                val noLimitsText =
                                    RetrofitClient.api.chatCompletion(requestNoLimits)
                                        .choices.firstOrNull()?.message?.content
                                        ?: "Нет ответа"


                                // 🔹 Общий контролируемый prompt
                                val controlledPrompt = """
                                    Объясни чёрные дыры.
                                    
                                    Ответ строго в JSON формате:
                                    {
                                      "definition": "...",
                                      "formation": "...",
                                      "fact": "..."
                                    }
                                    
                                    Максимум 60 слов.
                                    Никакого текста вне JSON.
                                    Заверши вывод символом #.
                                """.trimIndent()


                                // 🔹 2. Ограничения + T=0.2
                                val requestCold = ChatRequest(
                                    model = "meta-llama/llama-3.1-8b-instruct",
                                    messages = listOf(
                                        Message("user", controlledPrompt)
                                    ),
                                    max_tokens = 150,
                                    temperature = 0.2,
                                    stop = listOf("#")
                                )

                                val coldText =
                                    RetrofitClient.api.chatCompletion(requestCold)
                                        .choices.firstOrNull()?.message?.content
                                        ?: "Нет ответа"


                                // 🔹 3. Ограничения + T=1.0
                                val requestHot = ChatRequest(
                                    model = "meta-llama/llama-3.1-8b-instruct",
                                    messages = listOf(
                                        Message("user", controlledPrompt)
                                    ),
                                    max_tokens = 150,
                                    temperature = 1.0,
                                    stop = listOf("#")
                                )

                                val hotText =
                                    RetrofitClient.api.chatCompletion(requestHot)
                                        .choices.firstOrNull()?.message?.content
                                        ?: "Нет ответа"


                                responseText = """
                                    ==============================
                                    🔹 1. БЕЗ ОГРАНИЧЕНИЙ
                                    ==============================
                                    
                                    $noLimitsText
                                    
                                    
                                    ==============================
                                    🔹 2. С ОГРАНИЧЕНИЯМИ (T=0.2)
                                    ==============================
                                    
                                    $coldText
                                    
                                    
                                    ==============================
                                    🔹 3. С ОГРАНИЧЕНИЯМИ (T=1.0)
                                    ==============================
                                    
                                    $hotText
                                """.trimIndent()

                            } catch (e: Exception) {
                                responseText = "Ошибка: ${e.message}"
                            }

                            isLoading = false
                        }
                    }
                ) {
                    Text("Сравнить все варианты")
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isLoading) {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    Text(responseText)
                }
            }
        }
    }
}
