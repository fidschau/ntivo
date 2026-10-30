package io.ntivo.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ntivo.EmbeddingTaskType
import io.ntivo.TaskTypeEmbedder
import io.ntivo.VectorStore
import io.ntivo.pieceFromPayload
import kotlin.coroutines.cancellation.CancellationException

/** Guardrail: search results scoring below this are dropped before the model sees them. */
const val MIN_SIMILARITY = 0.60f

/** How many pieces one search returns at most. */
const val SEARCH_LIMIT = 5

/**
 * Search by meaning over the pieces of the ready sources ([sourceIds]).
 *
 * Read-only. Create one instance per question: every call is recorded in [trace],
 * and every returned piece becomes citable through [TraceRecorder.retrievedPieceIds].
 */
@LLMDescription("Search the connected repositories for code by meaning")
class CodeSearchTools(
    private val embedder: TaskTypeEmbedder,
    private val store: VectorStore,
    private val collection: String,
    private val sourceIds: List<String>,
    private val trace: TraceRecorder,
    private val minScore: Float = MIN_SIMILARITY,
    private val limit: Int = SEARCH_LIMIT
) : ToolSet {

    @Tool
    @LLMDescription(
        "Find code in the connected repositories that matches a description, by meaning rather than keywords. " +
            "Use it for any question about how the product or the code works. " +
            "Returns the closest functions, each with a [pieceId] header followed by its code in a <code-piece> block. " +
            "Do not use it for general programming questions, or when a previous search already answered the question."
    )
    suspend fun searchCodebase(
        @LLMDescription("What the code does, in plain words, e.g. 'maximum number of friends a member can invite'")
        query: String
    ): String = trace.guarded("searchCodebase", query) {
        try {
            search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // e.g. the embedding quota is spent or Qdrant is down: tell the model plainly
            "Search is unavailable right now. Say that the code could not be searched." to "failed: ${e.message}"
        }
    }

    private suspend fun search(query: String): Pair<String, String> {
        if (sourceIds.isEmpty()) {
            return "No sources are ready to search. Say that no code is available yet." to "no ready sources"
        }
        if (!store.collectionExists(collection)) {
            return "Nothing has been indexed yet. Say that no code is available yet." to "nothing indexed"
        }

        val vector = embedder.embed(query, EmbeddingTaskType.CODE_RETRIEVAL_QUERY)
        val results = store.search(collection, vector.values, limit, sourceIds, minScore)
            .map { pieceFromPayload(it.payloadMap) to it.score }
            .filter { (piece, _) -> piece.id.isNotEmpty() }

        if (results.isEmpty()) {
            return ("Nothing relevant found for '$query' (no piece above similarity ${"%.2f".format(minScore)}). " +
                "Say that nothing relevant was found unless another search finds it.") to "0 pieces above threshold"
        }

        results.forEach { (piece, score) -> trace.retrieved(piece, score) }

        val text = buildString {
            appendLine("Found ${results.size} piece${if (results.size == 1) "" else "s"}:")
            for ((piece, score) in results) {
                appendLine()
                appendLine(
                    "[${piece.id}] ${piece.sourceName} ${piece.path}:${piece.startLine}-${piece.endLine} " +
                        "${piece.qualifiedName.ifEmpty { piece.name }} (similarity ${"%.2f".format(score)})"
                )
                appendLine(wrapPiece(piece))
            }
        }.trimEnd()
        val top = results.first()
        return text to "${results.size} pieces, top ${top.first.name} ${"%.2f".format(top.second)}"
    }
}
