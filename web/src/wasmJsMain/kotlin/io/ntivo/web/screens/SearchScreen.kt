package io.ntivo.web.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ntivo.shared.SearchResultItem
import io.ntivo.web.api.NtivoApiClient
import io.ntivo.web.components.ResultPanel
import io.ntivo.web.components.ResultState
import io.ntivo.web.theme.NtivoError
import io.ntivo.web.theme.NtivoSuccess
import kotlin.math.pow
import kotlin.math.round
import kotlinx.coroutines.launch

private fun formatFloat(value: Float, decimals: Int): String {
    val factor = 10.0.pow(decimals)
    val rounded = round(value.toDouble() * factor) / factor
    val str = rounded.toString()
    val dotIndex = str.indexOf('.')
    return if (dotIndex < 0) "$str.${"0".repeat(decimals)}"
    else str.padEnd(dotIndex + 1 + decimals, '0').take(dotIndex + 1 + decimals)
}

@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    var collection by remember { mutableStateOf("chunks_demo") }
    var limit by remember { mutableStateOf("5") }
    var resultState by remember { mutableStateOf<ResultState>(ResultState.Empty) }
    var searchResults by remember { mutableStateOf<List<SearchResultItem>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val isLoading = resultState is ResultState.Loading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "Vector Search",
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
                text = "How this works: Your query gets embedded into a vector, then Qdrant finds " +
                    "the most similar stored vectors using cosine similarity. Higher score = closer match. " +
                    "Tip: First store some text on the Embed + Store tab, then search for it here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Natural language query",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "e.g. health check endpoint, user authentication, error handling...",
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

        Spacer(Modifier.height(16.dp))

        // Collection + limit row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Collection",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = collection,
                    onValueChange = { collection = it },
                    modifier = Modifier.fillMaxWidth(),
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

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Max results",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                OutlinedTextField(
                    value = limit,
                    onValueChange = { newValue ->
                        if (newValue.all { it.isDigit() } && newValue.length <= 2) {
                            limit = newValue
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
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

        Button(
            onClick = {
                val q = query.trim()
                if (q.isEmpty()) {
                    resultState = ResultState.Error("Query cannot be empty.")
                    return@Button
                }
                val parsedLimit = limit.toIntOrNull()?.coerceIn(1, 20) ?: 5
                resultState = ResultState.Loading
                searchResults = emptyList()
                scope.launch {
                    try {
                        val response = NtivoApiClient.search(q, collection.trim().ifEmpty { "chunks_demo" }, parsedLimit)
                        if (response.results.isEmpty()) {
                            searchResults = emptyList()
                            resultState = ResultState.Success(
                                "No results found. Try storing some text first on the Embed + Store tab."
                            )
                        } else {
                            searchResults = response.results
                            resultState = ResultState.Empty // We'll render custom cards instead
                        }
                    } catch (e: Exception) {
                        searchResults = emptyList()
                        resultState = ResultState.Error(
                            "Search failed.\n\nError: ${e.message}\n\n" +
                                "Make sure:\n" +
                                "  1. Server is running: ./gradlew :server:run\n" +
                                "  2. NTIVO_GEMINI_API_KEY is set\n" +
                                "  3. Qdrant is running: docker compose up -d"
                        )
                    }
                }
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Search")
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = "Results",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (searchResults.isNotEmpty()) {
            searchResults.forEachIndexed { index, item ->
                SearchResultCard(index = index + 1, item = item)
                Spacer(Modifier.height(8.dp))
            }
        } else {
            ResultPanel(
                state = resultState,
                label = ""
            )
        }
    }
}

@Composable
private fun SearchResultCard(index: Int, item: SearchResultItem) {
    val scoreColor = when {
        item.score > 0.7f -> NtivoSuccess
        item.score > 0.5f -> Color(0xFFFFD43B) // yellow
        else -> NtivoError
    }

    val shape = RoundedCornerShape(6.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF12122A), shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape)
            .padding(12.dp)
    ) {
        // Score + label header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "#$index  Score: ${formatFloat(item.score, 4)}",
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )

            val itemLabel = item.payload["label"]
            if (itemLabel != null && itemLabel != "Unlabeled") {
                Text(
                    text = itemLabel,
                    fontSize = 12.sp,
                    color = NtivoSuccess,
                    modifier = Modifier
                        .background(Color(0xFF1A2E1A), RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }

        // Text content
        val textContent = item.payload["text"]
        if (textContent != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(4.dp))
                    .padding(10.dp)
                    .heightIn(max = 120.dp)
            ) {
                Text(
                    text = textContent,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            }
        }

        // Metadata footer
        val metaKeys = item.payload.keys.filter { it != "text" && it != "label" }
        if (metaKeys.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = metaKeys.joinToString("  |  ") { key -> "$key: ${item.payload[key]}" },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    }
}
