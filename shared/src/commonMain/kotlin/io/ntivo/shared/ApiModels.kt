package io.ntivo.shared

import kotlinx.serialization.Serializable

// Health
@Serializable
data class HealthResponse(val status: String)

// Chat
@Serializable
data class ChatRequest(val prompt: String)

@Serializable
data class ChatResponse(val response: String)

// Embedding
@Serializable
data class EmbedRequest(val text: String, val taskType: String = "RETRIEVAL_DOCUMENT")

@Serializable
data class EmbedResponse(val dimension: Int, val preview: List<Double>, val taskType: String)

// Store
@Serializable
data class StoreRequest(val text: String, val label: String = "", val collection: String = "chunks_demo")

@Serializable
data class StoreResponse(val stored: Boolean, val collection: String, val dimension: Int, val pointId: Long)

// Search
@Serializable
data class SearchRequest(val query: String, val collection: String = "chunks_demo", val limit: Int = 5)

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

// Error
@Serializable
data class ErrorResponse(val error: String)
