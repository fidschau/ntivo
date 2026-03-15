package io.ntivo.web.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ntivo.web.api.NtivoApiClient
import io.ntivo.web.components.ResultPanel
import io.ntivo.web.components.ResultState
import kotlin.math.pow
import kotlin.math.round
import kotlinx.coroutines.launch

private fun formatDouble(value: Double, decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = round(value * factor) / factor
    val str = rounded.toString()
    val dotIndex = str.indexOf('.')
    return if (dotIndex < 0) "$str.${"0".repeat(decimals)}"
    else str.padEnd(dotIndex + 1 + decimals, '0').take(dotIndex + 1 + decimals)
}

private val TASK_TYPES = listOf(
    "RETRIEVAL_DOCUMENT" to "RETRIEVAL_DOCUMENT (indexing code/docs)",
    "RETRIEVAL_QUERY" to "RETRIEVAL_QUERY (search queries)",
    "CODE_RETRIEVAL_QUERY" to "CODE_RETRIEVAL_QUERY (code search)",
    "SEMANTIC_SIMILARITY" to "SEMANTIC_SIMILARITY (drift detection)",
    "CLASSIFICATION" to "CLASSIFICATION",
    "CLUSTERING" to "CLUSTERING"
)

@Composable
fun EmbedScreen(modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    var taskType by remember { mutableStateOf("RETRIEVAL_DOCUMENT") }
    var label by remember { mutableStateOf("") }
    var taskTypeExpanded by remember { mutableStateOf(false) }
    var resultState by remember { mutableStateOf<ResultState>(ResultState.Empty) }
    val scope = rememberCoroutineScope()
    val isLoading = resultState is ResultState.Loading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "Embed + Store",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        // Hint box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(6.dp)
                )
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp)
                )
                .padding(14.dp)
        ) {
            Text(
                text = "How this works: Gemini converts your text into a 3072-dimensional vector " +
                    "(a list of numbers that capture its meaning). Similar texts produce similar vectors. " +
                    "Embed shows you the raw vector. Embed & Store saves it to Qdrant so you can search for it " +
                    "on the Vector Search tab.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Text or code to embed",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
            placeholder = {
                Text(
                    "Paste code or text to embed...",
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

        // Task type + label row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Task type dropdown
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Task Type",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                Box {
                    OutlinedTextField(
                        value = TASK_TYPES.first { it.first == taskType }.second,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                    DropdownMenu(
                        expanded = taskTypeExpanded,
                        onDismissRequest = { taskTypeExpanded = false }
                    ) {
                        TASK_TYPES.forEach { (value, display) ->
                            DropdownMenuItem(
                                text = { Text(display, style = MaterialTheme.typography.bodyMedium) },
                                onClick = {
                                    taskType = value
                                    taskTypeExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Label field
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Label (for stored vectors)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "e.g. health check function",
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
                    enabled = !isLoading
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Buttons row
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = {
                    val input = text.trim()
                    if (input.isEmpty()) {
                        resultState = ResultState.Error("Text cannot be empty.")
                        return@OutlinedButton
                    }
                    resultState = ResultState.Loading
                    scope.launch {
                        try {
                            val response = NtivoApiClient.embed(input, taskType)
                            val preview = response.preview.joinToString(", ") {
                                formatDouble(it, 6)
                            }
                            resultState = ResultState.Success(
                                "Dimension: ${response.dimension}  |  Task type: ${response.taskType}\n\n" +
                                    "Vector preview (first 10 of ${response.dimension} values):\n" +
                                    "[$preview, ...]\n\n" +
                                    "These numbers capture the \"meaning\" of your text. Similar texts produce\n" +
                                    "similar numbers. Use \"Embed & Store\" to save this to Qdrant for searching."
                            )
                        } catch (e: Exception) {
                            resultState = ResultState.Error(
                                "Failed to embed text.\n\nError: ${e.message}\n\n" +
                                    "Make sure NTIVO_GEMINI_API_KEY is set."
                            )
                        }
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Embed Only")
            }

            Button(
                onClick = {
                    val input = text.trim()
                    if (input.isEmpty()) {
                        resultState = ResultState.Error("Text cannot be empty.")
                        return@Button
                    }
                    resultState = ResultState.Loading
                    scope.launch {
                        try {
                            val response = NtivoApiClient.store(input, label.trim(), "chunks_demo")
                            resultState = ResultState.Success(
                                "Stored in Qdrant!\n\n" +
                                    "  Collection:  ${response.collection}\n" +
                                    "  Dimension:   ${response.dimension}\n" +
                                    "  Point ID:    ${response.pointId}\n\n" +
                                    "Your text has been embedded and saved. Go to the \"Vector Search\"\n" +
                                    "tab and search for something related to find it."
                            )
                        } catch (e: Exception) {
                            resultState = ResultState.Error(
                                "Failed to store.\n\nError: ${e.message}\n\n" +
                                    "Make sure:\n" +
                                    "  1. NTIVO_GEMINI_API_KEY is set\n" +
                                    "  2. Qdrant is running: docker compose up -d"
                            )
                        }
                    }
                },
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Embed & Store in Qdrant")
            }
        }

        Spacer(Modifier.height(20.dp))

        ResultPanel(
            state = resultState,
            label = "Result"
        )
    }
}
