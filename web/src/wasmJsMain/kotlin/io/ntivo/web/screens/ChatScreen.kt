package io.ntivo.web.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ntivo.web.api.NtivoApiClient
import io.ntivo.web.components.ResultPanel
import io.ntivo.web.components.ResultState
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    var prompt by remember { mutableStateOf("") }
    var resultState by remember { mutableStateOf<ResultState>(ResultState.Empty) }
    val scope = rememberCoroutineScope()
    val isLoading = resultState is ResultState.Loading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "Agent Chat",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Send prompts to the Koog agent powered by Gemini 2.5 Flash.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Prompt",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            placeholder = {
                Text(
                    "Ask the Koog agent anything...",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            },
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            enabled = !isLoading
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                val text = prompt.trim()
                if (text.isEmpty()) {
                    resultState = ResultState.Error("Prompt cannot be empty.")
                    return@Button
                }
                resultState = ResultState.Loading
                scope.launch {
                    try {
                        val response = NtivoApiClient.chat(text)
                        resultState = ResultState.Success(response.response)
                    } catch (e: Exception) {
                        resultState = ResultState.Error(
                            "Failed to get agent response.\n\n" +
                                "Error: ${e.message}\n\n" +
                                "Make sure:\n" +
                                "  1. Server is running: ./gradlew :server:run\n" +
                                "  2. NTIVO_GEMINI_API_KEY is set"
                        )
                    }
                }
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Send")
        }

        Spacer(Modifier.height(20.dp))

        ResultPanel(
            state = resultState,
            label = "Response"
        )
    }
}
