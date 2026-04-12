package io.ntivo.web.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ntivo.shared.PipelineStep
import io.ntivo.shared.RagPipelineResponse
import io.ntivo.shared.SearchResultItem
import io.ntivo.web.api.NtivoApiClient
import io.ntivo.web.components.ResultPanel
import io.ntivo.web.components.ResultState
import io.ntivo.web.theme.NtivoSuccess
import kotlinx.coroutines.launch
import kotlin.math.pow
import kotlin.math.round

private fun formatFloat(value: Float, decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = round(value.toDouble() * factor) / factor
    val str = rounded.toString()
    val dotIndex = str.indexOf('.')
    return if (dotIndex < 0) "$str.${"0".repeat(decimals)}"
    else str.padEnd(dotIndex + 1 + decimals, '0').take(dotIndex + 1 + decimals)
}

private val SAMPLE_CODE = """class AuthService {
    fun authenticate(user: User): Boolean {
        return user.password == hash(user.input)
    }

    fun logout(sessionId: String) {
        sessions.remove(sessionId)
    }

    fun resetPassword(email: String): Token {
        val user = findByEmail(email)
        return generateResetToken(user)
    }
}""".trimIndent()

@Composable
fun RagPipelineScreen(modifier: Modifier = Modifier) {
    var code by remember { mutableStateOf(SAMPLE_CODE) }
    var pipelineState by remember { mutableStateOf<ResultState>(ResultState.Empty) }
    var pipelineResponse by remember { mutableStateOf<RagPipelineResponse?>(null) }
    var query by remember { mutableStateOf("") }
    var chatState by remember { mutableStateOf<ResultState>(ResultState.Empty) }
    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val isPipelineLoading = pipelineState is ResultState.Loading
    val isChatLoading = chatState is ResultState.Loading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "RAG Pipeline",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(4.dp))

        Text(
            text = "Paste code, hit Process to run the full pipeline (parse, chunk, embed, store), then search.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        // ─── Code Input Section ───
        Text(
            text = "Source code",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 250.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            enabled = !isPipelineLoading
        )

        Spacer(Modifier.height(12.dp))

        // ─── Process Button ───
        Button(
            onClick = {
                val input = code.trim()
                if (input.isEmpty()) {
                    pipelineState = ResultState.Error("Code cannot be empty.")
                    return@Button
                }
                pipelineState = ResultState.Loading
                pipelineResponse = null
                scope.launch {
                    try {
                        val response = NtivoApiClient.ragPipeline(input)
                        pipelineResponse = response
                        val allSuccess = response.steps.all { it.success }
                        if (allSuccess) {
                            pipelineState = ResultState.Empty
                        } else {
                            pipelineState = ResultState.Error("Pipeline completed with errors")
                        }
                    } catch (e: Exception) {
                        pipelineState = ResultState.Error(
                            "Pipeline failed.\n\nError: ${e.message}\n\n" +
                                "Make sure:\n" +
                                "  1. NTIVO_GEMINI_API_KEY is set\n" +
                                "  2. Docker is running: docker compose up -d"
                        )
                    }
                }
            },
            enabled = !isPipelineLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (isPipelineLoading) {
                Text("Processing...")
            } else {
                Text("Process: Parse, Chunk, Embed, Store")
            }
        }

        Spacer(Modifier.height(16.dp))

        // ─── Pipeline Output ───
        if (pipelineState is ResultState.Loading) {
            ResultPanel(state = ResultState.Loading, label = "Pipeline output")
        } else if (pipelineState is ResultState.Error) {
            ResultPanel(state = pipelineState, label = "Pipeline output")
        } else if (pipelineResponse != null) {
            PipelineOutputSection(pipelineResponse!!)
        }

        Spacer(Modifier.height(20.dp))

        // ─── Chat Search Section ───
        if (pipelineResponse != null && pipelineResponse!!.chunksIndexed > 0) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(16.dp))

            Text(
                text = "Search your code",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Chat history
            if (chatMessages.isNotEmpty()) {
                ChatHistory(chatMessages)
                Spacer(Modifier.height(12.dp))
            }

            // Chat input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            "Ask about your code...",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    singleLine = true,
                    enabled = !isChatLoading
                )

                Button(
                    onClick = {
                        val q = query.trim()
                        if (q.isEmpty()) return@Button
                        chatState = ResultState.Loading
                        val userMsg = ChatMessage("You", q, ChatMessageType.USER)
                        chatMessages = chatMessages + userMsg
                        query = ""
                        scope.launch {
                            try {
                                // Search Qdrant
                                val searchResponse = NtivoApiClient.search(q, limit = 3)
                                val results = searchResponse.results

                                // Show tool call
                                val toolMsg = ChatMessage(
                                    "tool",
                                    "search_codebase(\"$q\"): ${results.size} results" +
                                        if (results.isNotEmpty()) " (top: ${formatFloat(results[0].score, 2)})" else "",
                                    ChatMessageType.TOOL_CALL
                                )
                                chatMessages = chatMessages + toolMsg

                                if (results.isEmpty()) {
                                    chatMessages = chatMessages + ChatMessage(
                                        "Ntivo",
                                        "No matching code found. Try indexing more code first.",
                                        ChatMessageType.AGENT
                                    )
                                    chatState = ResultState.Empty
                                    return@launch
                                }

                                // Build context and ask agent
                                val context = results.joinToString("\n\n") { r ->
                                    val label = r.payload["label"] ?: "unknown"
                                    val code = r.payload["text"] ?: ""
                                    "Function: $label (similarity: ${formatFloat(r.score, 2)})\n$code"
                                }

                                val agentPrompt = "Based on these code search results, answer the question: \"$q\"\n\n$context"
                                val agentResponse = NtivoApiClient.chat(agentPrompt)

                                chatMessages = chatMessages + ChatMessage(
                                    "Ntivo",
                                    agentResponse.response,
                                    ChatMessageType.AGENT
                                )
                                chatState = ResultState.Empty
                            } catch (e: Exception) {
                                chatMessages = chatMessages + ChatMessage(
                                    "Ntivo",
                                    "Error: ${e.message}",
                                    ChatMessageType.ERROR
                                )
                                chatState = ResultState.Empty
                            }
                        }
                    },
                    enabled = !isChatLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(if (isChatLoading) "..." else "Ask")
                }
            }
        }
    }
}

// ─── Pipeline Output ───

@Composable
private fun PipelineOutputSection(response: RagPipelineResponse) {
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(14.dp)
    ) {
        Text(
            text = "Pipeline output",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        val resultText = buildString {
            for (step in response.steps) {
                val prefix = "[${step.stage}]".padEnd(10)
                appendLine("$prefix ${step.message}")
            }
            appendLine()
            val status = if (response.chunksIndexed > 0) "Done" else "Failed"
            append("$status - ${response.chunksIndexed} chunks indexed in ${response.totalTime}")
        }

        ResultPanel(state = ResultState.Success(resultText), label = "")
    }
}

// ─── Chat Components ───

private enum class ChatMessageType { USER, AGENT, TOOL_CALL, ERROR }

private data class ChatMessage(
    val sender: String,
    val content: String,
    val type: ChatMessageType
)

@Composable
private fun ChatHistory(messages: List<ChatMessage>) {
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (msg in messages) {
            when (msg.type) {
                ChatMessageType.USER -> {
                    Text(
                        text = "You",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = msg.content,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                ChatMessageType.TOOL_CALL -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(5.dp)
                            )
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                RoundedCornerShape(5.dp)
                            )
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "🔧 ${msg.content}",
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                ChatMessageType.AGENT -> {
                    Text(
                        text = "Ntivo",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NtivoSuccess
                    )
                    Text(
                        text = msg.content,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 20.sp
                    )
                }
                ChatMessageType.ERROR -> {
                    Text(
                        text = msg.content,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
