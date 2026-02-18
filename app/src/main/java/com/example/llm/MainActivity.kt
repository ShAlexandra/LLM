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

                Button(onClick = {
                    scope.launch {

                        isLoading = true
                        responseText = "Выполняется..."

                        try {

                            val baseInstruction = """
                                Напиши корректную реализацию алгоритма быстрой сортировки (QuickSort) на Kotlin.
                                Ответ пиши на русском языке.
                                Обязательно выведи рабочий код.
                                Код должен быть в блоке ```kotlin```.
                                Не добавляй лишнего текста вне блока кода.
                            """.trimIndent()

                            // =====================================================
                            // 1️⃣ Прямой ответ
                            // =====================================================

                            val directAnswer =
                                RetrofitClient.api.chatCompletion(
                                    ChatRequest(
                                        model = "meta-llama/llama-3.1-8b-instruct",
                                        messages = listOf(
                                            Message("user", baseInstruction)
                                        )
                                    )
                                ).choices.firstOrNull()?.message?.content
                                    ?: "Нет ответа"


                            // =====================================================
                            // 2️⃣ Пошагово
                            // =====================================================

                            val stepPrompt = """
                                Напиши алгоритм QuickSort на Kotlin.
                                Решай пошагово.
                                Сначала кратко объясни шаги на русском,
                                затем выведи финальный рабочий код.
                                Код обязательно в блоке ```kotlin```.
                                Без лишнего текста после кода.
                            """.trimIndent()

                            val stepAnswer =
                                RetrofitClient.api.chatCompletion(
                                    ChatRequest(
                                        model = "meta-llama/llama-3.1-8b-instruct",
                                        messages = listOf(
                                            Message("user", stepPrompt)
                                        )
                                    )
                                ).choices.firstOrNull()?.message?.content
                                    ?: "Нет ответа"


                            // =====================================================
                            // 3️⃣ Сначала создать идеальный промпт
                            // =====================================================

                            val promptGenerator = """
                                Составь идеальный промпт,
                                который позволит получить максимально корректную,
                                оптимизированную и компилируемую реализацию QuickSort на Kotlin.
                                Ответ только текст промпта.
                                На русском.
                            """.trimIndent()

                            val generatedPrompt =
                                RetrofitClient.api.chatCompletion(
                                    ChatRequest(
                                        model = "meta-llama/llama-3.1-8b-instruct",
                                        messages = listOf(
                                            Message("user", promptGenerator)
                                        )
                                    )
                                ).choices.firstOrNull()?.message?.content
                                    ?: "Нет промпта"


                            val generatedAnswer =
                                RetrofitClient.api.chatCompletion(
                                    ChatRequest(
                                        model = "meta-llama/llama-3.1-8b-instruct",
                                        messages = listOf(
                                            Message("user", generatedPrompt)
                                        )
                                    )
                                ).choices.firstOrNull()?.message?.content
                                    ?: "Нет ответа"


                            // =====================================================
                            // 4️⃣ Группа экспертов
                            // =====================================================

                            val expertsPrompt = """
                                Задача: написать QuickSort на Kotlin.
                                
                                Работает группа экспертов:
                                
                                1. Аналитик — кратко объясняет алгоритм.
                                2. Инженер — пишет рабочий код QuickSort.
                                3. Критик — проверяет код и предлагает улучшения.
                                
                                Ответ на русском языке.
                                Код обязателен и должен быть в блоке ```kotlin```.
                                Без лишнего текста вне структуры ролей.
                            """.trimIndent()

                            val expertsAnswer =
                                RetrofitClient.api.chatCompletion(
                                    ChatRequest(
                                        model = "meta-llama/llama-3.1-8b-instruct",
                                        messages = listOf(
                                            Message("user", expertsPrompt)
                                        )
                                    )
                                ).choices.firstOrNull()?.message?.content
                                    ?: "Нет ответа"


                            // =====================================================
                            // Итог
                            // =====================================================

                            responseText = """
======================================
1️⃣ ПРЯМОЙ ОТВЕТ
======================================

$directAnswer


======================================
2️⃣ ПОШАГОВО
======================================

$stepAnswer


======================================
3️⃣ ЧЕРЕЗ СГЕНЕРИРОВАННЫЙ ПРОМПТ
======================================

Сгенерированный промпт:
--------------------------------------
$generatedPrompt

Результат:
--------------------------------------
$generatedAnswer


======================================
4️⃣ ГРУППА ЭКСПЕРТОВ
======================================

$expertsAnswer
                            """.trimIndent()

                        } catch (e: Exception) {
                            responseText = "Ошибка: ${e.message}"
                        }

                        isLoading = false
                    }
                }) {
                    Text("Запустить 4 стратегии")
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