package io.ntivo

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Task types for Gemini embedding API.
 * Always specify a task type — it changes the embedding space.
 *
 * @see <a href="https://ai.google.dev/gemini-api/docs/embeddings">Gemini Embeddings docs</a>
 */
enum class EmbeddingTaskType {
    /** Use when indexing documents/code for later retrieval. */
    RETRIEVAL_DOCUMENT,
    /** Use when embedding a user's search query. */
    RETRIEVAL_QUERY,
    /** Use when embedding code search queries specifically. */
    CODE_RETRIEVAL_QUERY,
    /** Use for comparing similarity between two texts (e.g. drift detection). */
    SEMANTIC_SIMILARITY,
    /** Use for text classification tasks. */
    CLASSIFICATION,
    /** Use for clustering tasks. */
    CLUSTERING
}

// --- Gemini REST API request/response models ---

@Serializable
private data class GeminiEmbedRequest(
    val model: String,
    val content: GeminiContent,
    val taskType: String
)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiEmbedResponse(val embedding: GeminiEmbeddingValues)

@Serializable
private data class GeminiEmbeddingValues(val values: List<Double>)

/**
 * Embedder that calls the Gemini embedding REST API directly,
 * with proper taskType support.
 *
 * Implements Koog's [Embedder] interface so it plugs into
 * Koog's RAG/vector-storage stack.
 *
 * Usage:
 * ```
 * val embedder = GeminiEmbedder(apiKey)
 * val vector = embedder.embed("some code", EmbeddingTaskType.RETRIEVAL_DOCUMENT)
 * ```
 *
 * @param apiKey Gemini API key (from NTIVO_GEMINI_API_KEY env var)
 * @param model Embedding model name (default: gemini-embedding-001)
 * @param defaultTaskType Task type used when calling the no-arg [embed] method
 */
class GeminiEmbedder(
    private val apiKey: String,
    private val model: String = "gemini-embedding-001",
    private val defaultTaskType: EmbeddingTaskType = EmbeddingTaskType.RETRIEVAL_DOCUMENT
) : Embedder {

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    /**
     * Embed text with an explicit task type.
     * Prefer this over the no-arg version for clarity.
     */
    suspend fun embed(text: String, taskType: EmbeddingTaskType): Vector {
        val request = GeminiEmbedRequest(
            model = "models/$model",
            content = GeminiContent(parts = listOf(GeminiPart(text))),
            taskType = taskType.name
        )

        val response: GeminiEmbedResponse = httpClient.post(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:embedContent"
        ) {
            parameter("key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        return Vector(response.embedding.values)
    }

    /** Embed text using the [defaultTaskType]. Implements Koog's [Embedder] interface. */
    override suspend fun embed(text: String): Vector = embed(text, defaultTaskType)

    /** Cosine distance between two vectors. Implements Koog's [Embedder] interface. */
    override fun diff(embedding1: Vector, embedding2: Vector): Double {
        return 1.0 - embedding1.cosineSimilarity(embedding2)
    }

    fun close() {
        httpClient.close()
    }
}
