package io.ntivo.web.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ntivo.web.api.NtivoApiClient
import io.ntivo.web.components.ResultPanel
import io.ntivo.web.components.ResultState
import kotlinx.coroutines.launch

/** Starter Cypher queries to paste into the Neo4j Browser after indexing. */
private val EXAMPLE_QUERIES = listOf(
    "Everything one file declares" to
        "MATCH p = (:File {name: 'ApiRouting.kt'})-[:DECLARES*]->() RETURN p",
    "Who calls embed()?" to
        "MATCH p = (:Function)-[:CALLS]->(:Function {name: 'embed'}) RETURN p",
    "Blast radius: what reaches search() within 3 hops" to
        "MATCH p = (:Function)-[:CALLS*1..3]->(:Function {name: 'search'}) RETURN p",
    "Ambiguous edges (name matched more than one function)" to
        "MATCH (a)-[c:CALLS]->(b) WHERE c.confidence < 1\n" +
        "RETURN a.qualifiedName, b.qualifiedName, c.confidence ORDER BY c.confidence",
    "Most-called functions" to
        "MATCH (f:Function)<-[c:CALLS]-() RETURN f.qualifiedName, count(c) AS callers\n" +
        "ORDER BY callers DESC LIMIT 10",
    "Class hierarchy" to
        "MATCH p = (:Class)-[:INHERITS]->(:Class) RETURN p"
)

@Composable
fun GraphScreen(modifier: Modifier = Modifier) {
    var path by remember { mutableStateOf("") }
    var resultState by remember { mutableStateOf<ResultState>(ResultState.Empty) }
    val scope = rememberCoroutineScope()
    val isLoading = resultState is ResultState.Loading

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
    ) {
        Text(
            text = "Code Graph",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Parse every .kt file under a folder into Neo4j: files, classes and functions, " +
                "linked by DECLARES, CALLS and INHERITS. Each run rebuilds the graph. " +
                "Explore the result in the Neo4j Browser at localhost:7474 (neo4j / ntivo_dev_password).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text("Absolute path to a Kotlin source folder") },
            placeholder = { Text("/Users/you/IntellijProjects/ntivo") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading
        )

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val p = path.trim()
                if (p.isEmpty()) {
                    resultState = ResultState.Error("Path cannot be empty.")
                    return@Button
                }
                resultState = ResultState.Loading
                scope.launch {
                    resultState = try {
                        val r = NtivoApiClient.indexGraph(p)
                        ResultState.Success(
                            "Indexed ${r.root} in ${r.durationMs}ms\n\n" +
                                "Nodes\n" +
                                "  ${r.files} files\n" +
                                "  ${r.classes} classes\n" +
                                "  ${r.functions} functions\n\n" +
                                "Edges\n" +
                                "  ${r.callEdges} CALLS (${r.ambiguousCallEdges} ambiguous, confidence < 1)\n" +
                                "  ${r.inheritsEdges} INHERITS"
                        )
                    } catch (e: Exception) {
                        ResultState.Error(
                            "Indexing failed.\n\nError: ${e.message}\n\n" +
                                "Make sure Neo4j is running: docker compose up -d"
                        )
                    }
                }
            },
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Build Graph")
        }

        Spacer(Modifier.height(20.dp))

        ResultPanel(state = resultState, label = "Result")

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Try these in the Neo4j Browser",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((title, query) in EXAMPLE_QUERIES) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = query,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
