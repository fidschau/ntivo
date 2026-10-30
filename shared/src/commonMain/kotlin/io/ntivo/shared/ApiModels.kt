package io.ntivo.shared

import kotlinx.serialization.Serializable

/** Qdrant collection used when a request doesn't name one. Single-tenant for now. */
const val DEFAULT_COLLECTION = "code_chunks"

// Health
@Serializable
data class HealthResponse(val status: String)

// Search
@Serializable
data class SearchRequest(val query: String, val collection: String = DEFAULT_COLLECTION, val limit: Int = 5)

@Serializable
data class SearchResponse(val results: List<SearchResultItem>)

@Serializable
data class SearchResultItem(val score: Float, val payload: Map<String, String>)

// Parse
@Serializable
data class ParseRequest(val code: String)

@Serializable
data class ParseResponse(val functions: List<ParsedFunction>, val classes: List<String>, val nodeCount: Int)

@Serializable
data class ParsedFunction(
    val name: String,
    val receiver: String? = null,
    val parameters: String,
    val startLine: Int,
    val endLine: Int,
    val bodyLength: Int
)

// RAG Pipeline
@Serializable
data class RagPipelineRequest(val code: String, val collection: String = DEFAULT_COLLECTION)

@Serializable
data class RagPipelineResponse(
    val steps: List<PipelineStep>,
    val chunksIndexed: Int,
    val totalTime: String,
    val chunksSkipped: Int = 0
)

@Serializable
data class PipelineStep(
    val stage: String,
    val message: String,
    val success: Boolean,
    val durationMs: Long
)

// Agent Chat (with tool calls)
@Serializable
data class AgentChatRequest(val prompt: String, val collection: String = DEFAULT_COLLECTION)

@Serializable
data class AgentChatResponse(
    val response: String,
    val toolCalls: List<AgentToolCall> = emptyList()
)

@Serializable
data class AgentToolCall(
    val tool: String,
    val args: String,
    val result: String
)

// Code Graph
@Serializable
data class GraphIndexRequest(val path: String)

@Serializable
data class GraphIndexResponse(
    val root: String,
    val files: Int,
    val classes: Int,
    val functions: Int,
    val callEdges: Int,
    val ambiguousCallEdges: Int,
    val inheritsEdges: Int,
    val durationMs: Long
)

// Error
@Serializable
data class ErrorResponse(val error: String)
