package io.ntivo.web.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ChatScreen(modifier: Modifier = Modifier) {
    StubContent(
        title = "Agent Chat",
        description = "Send prompts to the Koog agent powered by Gemini 2.5 Flash.",
        modifier = modifier
    )
}

@Composable
fun EmbedScreen(modifier: Modifier = Modifier) {
    StubContent(
        title = "Embed + Store",
        description = "Embed text with Gemini embeddings and store vectors in Qdrant.",
        modifier = modifier
    )
}

@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    StubContent(
        title = "Vector Search",
        description = "Query Qdrant with natural language to find semantically similar content.",
        modifier = modifier
    )
}

@Composable
fun ParseScreen(modifier: Modifier = Modifier) {
    StubContent(
        title = "Tree-sitter Parse",
        description = "Parse Kotlin code and extract function/class declarations.",
        modifier = modifier
    )
}

@Composable
private fun StubContent(
    title: String,
    description: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Text(
            text = "Coming soon — will be implemented in the next phase.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}
