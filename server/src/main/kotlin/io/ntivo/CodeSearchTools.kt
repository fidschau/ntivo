package io.ntivo

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

/** Record of a tool call made during an agent run. */
data class ToolCallRecord(
    val tool: String,
    val args: String,
    val resultSummary: String
)

/**
 * Koog ToolSet that gives the agent the ability to search indexed code.
 *
 * The agent decides when to call [searchCodebase] based on the user's question.
 * Create one instance per agent run: [callLog] records only that run's calls.
 */
@LLMDescription("Tools for searching an indexed codebase using semantic vector search")
class CodeSearchTools(
    private val embedder: TaskTypeEmbedder,
    private val store: VectorStore,
    private val collection: String
) : ToolSet {

    private val _callLog = mutableListOf<ToolCallRecord>()
    val callLog: List<ToolCallRecord> get() = _callLog.toList()

    @Tool
    @LLMDescription(
        "Search the indexed codebase for code relevant to a query. " +
        "Use this when the user asks about how code works, what functions exist, " +
        "or anything that requires looking at actual source code. " +
        "Returns the most relevant code chunks with similarity scores."
    )
    suspend fun searchCodebase(
        @LLMDescription("The search query describing what code to find, e.g. 'authentication logic' or 'payment processing'")
        query: String
    ): String {
        return try {
            if (!store.collectionExists(collection)) {
                _callLog.add(ToolCallRecord("searchCodebase", query, "collection '$collection' missing"))
                return "No code has been indexed yet. The collection '$collection' does not exist. " +
                    "Ask the user to index some code first using the RAG Pipeline."
            }

            // Embed the query with CODE_RETRIEVAL_QUERY task type
            val queryVector = embedder.embed(query, EmbeddingTaskType.CODE_RETRIEVAL_QUERY)
            val results = store.search(collection, queryVector.values, limit = 3)

            if (results.isEmpty()) {
                _callLog.add(ToolCallRecord("searchCodebase", query, "0 results"))
                return "No matching code found for query: '$query'. " +
                    "The indexed code may not contain anything related to this topic."
            }

            _callLog.add(ToolCallRecord(
                "searchCodebase", query,
                "${results.size} results (top: ${"%.2f".format(results[0].score)})"
            ))

            // Format results for the agent to read
            buildString {
                appendLine("Found ${results.size} matching code chunks:")
                appendLine()
                for ((i, result) in results.withIndex()) {
                    val score = "%.2f".format(result.score)
                    val label = result.payloadMap["label"]?.stringValue ?: "unknown"
                    val text = result.payloadMap["text"]?.stringValue ?: ""
                    val startLine = result.payloadMap["startLine"]?.integerValue
                    val endLine = result.payloadMap["endLine"]?.integerValue

                    appendLine("--- Result ${i + 1} (similarity: $score) ---")
                    appendLine("Function: $label")
                    if (startLine != null && endLine != null) {
                        appendLine("Lines: $startLine-$endLine")
                    }
                    appendLine(text)
                    appendLine()
                }
            }
        } catch (e: Exception) {
            _callLog.add(ToolCallRecord("searchCodebase", query, "failed: ${e.message}"))
            "Search failed: ${e.message}"
        }
    }
}
