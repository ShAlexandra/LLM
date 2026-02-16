package com.example.llm

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
            val scope = rememberCoroutineScope()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {

                Button(onClick = {
                    scope.launch {
                        try {
                            val request = ChatRequest(
                                model = "meta-llama/llama-3.1-8b-instruct",
                                messages = listOf(
                                    Message("user", "Объясни чёрные дыры просто")
                                )
                            )

                            val response = RetrofitClient.api.chatCompletion(request)
                            responseText =
                                response.choices.firstOrNull()?.message?.content
                                    ?: "Нет ответа"

                        } catch (e: Exception) {
                            responseText = "Ошибка: ${e.message}"
                        }
                    }
                }) {
                    Text("Отправить запрос")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(responseText)
            }
        }
    }
}
