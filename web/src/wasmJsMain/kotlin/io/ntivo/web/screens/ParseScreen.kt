package io.ntivo.web.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ntivo.shared.ParseResponse
import io.ntivo.web.api.NtivoApiClient
import io.ntivo.web.components.ResultPanel
import io.ntivo.web.components.ResultState
import io.ntivo.web.theme.NtivoSuccess
import kotlinx.coroutines.launch

private val SAMPLE_CODE = """package io.ntivo.example

import kotlinx.serialization.Serializable

@Serializable
data class CodeChunk(
    val id: String,
    val content: String,
    val fingerprint: String
)

fun CodeChunk.isStale(newFingerprint: String): Boolean {
    return fingerprint != newFingerprint
}

suspend fun indexRepository(orgId: String, repoUrl: String): Int {
    val chunks = parseAllFiles(repoUrl)
    val fresh = chunks.filter { !it.isStale(computeHash(it.content)) }
    return embedAndStore(orgId, fresh)
}

private fun computeHash(content: String): String {
    return java.security.MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray())
        .joinToString("") { "%02x".format(it) }
}""".trimIndent()

@Composable
fun ParseScreen(modifier: Modifier = Modifier) {
    var code by remember { mutableStateOf(SAMPLE_CODE) }
    var resultState by remember { mutableStateOf<ResultState>(ResultState.Empty) }
    var parseResult by remember { mutableStateOf<ParseResponse?>(null) }
    val scope = rememberCoroutineScope()
    val isLoading = resultState is ResultState.Loading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "Tree-sitter Parse",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Parse Kotlin code and extract function/class declarations using Tree-sitter.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Kotlin source code",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth().heightIn(min = 250.dp),
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
            enabled = !isLoading
        )

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (code.trim().isEmpty()) {
                    resultState = ResultState.Error("Code cannot be empty.")
                    return@Button
                }
                resultState = ResultState.Loading
                parseResult = null
                scope.launch {
                    try {
                        val response = NtivoApiClient.parse(code)
                        parseResult = response
                        resultState = ResultState.Empty // render custom UI
                    } catch (e: Exception) {
                        parseResult = null
                        resultState = ResultState.Error(
                            "Parse failed.\n\nError: ${e.message}\n\n" +
                                "Make sure the server is running: ./gradlew :server:run"
                        )
                    }
                }
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Parse")
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Extracted structure",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (parseResult != null) {
            ParseResultView(parseResult!!)
        } else {
            ResultPanel(state = resultState, label = "")
        }
    }
}

@Composable
private fun ParseResultView(result: ParseResponse) {
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(16.dp)
    ) {
        // Classes
        if (result.classes.isNotEmpty()) {
            Text(
                text = "Classes (${result.classes.size}): ${result.classes.joinToString(", ")}",
                color = NtivoSuccess,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(12.dp))
        }

        // Functions table
        if (result.functions.isNotEmpty()) {
            Text(
                text = "Functions (${result.functions.size}):",
                color = NtivoSuccess,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))

            // Table header
            val headerStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
            val cellStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp
            )

            // Scrollable table
            Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp)
                ) {
                    Text("Name", style = headerStyle, modifier = Modifier.width(180.dp))
                    Text("Receiver", style = headerStyle, modifier = Modifier.width(120.dp))
                    Text("Parameters", style = headerStyle, modifier = Modifier.width(200.dp))
                    Text("Lines", style = headerStyle, modifier = Modifier.width(80.dp))
                    Text("Size", style = headerStyle, modifier = Modifier.width(80.dp))
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline)

                result.functions.forEach { fn ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Text(fn.name, style = cellStyle, modifier = Modifier.width(180.dp))
                        Text(fn.receiver ?: "\u2014", style = cellStyle, modifier = Modifier.width(120.dp))
                        Text(fn.parameters, style = cellStyle, modifier = Modifier.width(200.dp))
                        Text("${fn.startLine}\u2013${fn.endLine}", style = cellStyle, modifier = Modifier.width(80.dp))
                        Text("${fn.bodyLength} chars", style = cellStyle, modifier = Modifier.width(80.dp))
                    }
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    )
                }
            }
        }

        if (result.functions.isEmpty() && result.classes.isEmpty()) {
            Text(
                text = "No functions or classes found.",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                fontSize = 13.sp
            )
        }

        // Footer with node count
        Spacer(Modifier.height(12.dp))
        Text(
            text = "AST: ${result.nodeCount} top-level nodes",
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}
