package io.ntivo

import ai.koog.embeddings.base.Embedder
import ai.koog.embeddings.base.Vector
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ntivo.agent.MIN_SIMILARITY
import io.ntivo.agent.redactSecrets
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ceil

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

/**
 * An [Embedder] that takes an explicit task type. Callers depend on this,
 * not on [GeminiEmbedder], so another model (e.g. Ollama) can be swapped in.
 */
interface TaskTypeEmbedder : Embedder {
    /** The most texts worth sending in one [embedAll] call; indexing batches by this. */
    val maxBatch: Int get() = 100

    /**
     * Search results scoring below this never reach the model. Each model spreads its
     * cosine scores differently, so the cut-off is calibrated per model; the default
     * ([MIN_SIMILARITY]) was set for Gemini.
     */
    val minSimilarity: Float get() = MIN_SIMILARITY

    suspend fun embed(text: String, taskType: EmbeddingTaskType): Vector

    /**
     * Embeds every text with the same [taskType], returning one vector per text in order.
     * The default embeds one at a time; implementations with a batch endpoint override it.
     */
    suspend fun embedAll(texts: List<String>, taskType: EmbeddingTaskType): List<Vector> =
        texts.map { embed(it, taskType) }
}

/**
 * The embedding API refused the request because this key is over its quota (HTTP 429).
 * [retryAfterSeconds] is the wait the API asked for, or null when it gave none.
 */
class EmbeddingRateLimited(
    val retryAfterSeconds: Int?,
    message: String = defaultMessage(retryAfterSeconds),
    cause: Throwable? = null
) : Exception(message, cause) {
    companion object {
        fun defaultMessage(retryAfterSeconds: Int?): String =
            "The Gemini embedding API is rate limiting this key (HTTP 429). The free tier allows " +
                "100 embedding requests per minute and about 1000 per day." +
                (retryAfterSeconds?.let { " The API asked to wait $it s." } ?: "")
    }
}

/**
 * The embedding API failed for a reason other than rate limiting. [status] is the HTTP
 * status, or null when no response arrived. The message never carries the API key, the
 * request URL or the raw response body.
 */
class EmbeddingRequestFailed(val status: Int?, message: String, cause: Throwable? = null) : Exception(message, cause)

// --- Gemini REST API request/response models ---

@Serializable
private data class GeminiEmbedRequest(
    val model: String,
    val content: GeminiContent,
    val taskType: String
)

@Serializable
private data class GeminiBatchEmbedRequest(val requests: List<GeminiEmbedRequest>)

@Serializable
private data class GeminiContent(val parts: List<GeminiPart>)

@Serializable
private data class GeminiPart(val text: String)

@Serializable
private data class GeminiEmbedResponse(val embedding: GeminiEmbeddingValues)

@Serializable
private data class GeminiBatchEmbedResponse(val embeddings: List<GeminiEmbeddingValues> = emptyList())

@Serializable
private data class GeminiEmbeddingValues(val values: List<Double>)

private val geminiJson = Json { ignoreUnknownKeys = true }

/**
 * Embedder that calls the Gemini embedding REST API directly,
 * with proper taskType support.
 *
 * Implements Koog's [Embedder] interface so it plugs into
 * Koog's RAG/vector-storage stack.
 *
 * The key is sent in the `x-goog-api-key` header, so request URLs (which Ktor quotes in
 * some exceptions) never carry it. Every response status is checked: a 429 throws
 * [EmbeddingRateLimited], any other non-2xx throws [EmbeddingRequestFailed].
 *
 * Usage:
 * ```
 * val embedder = GeminiEmbedder(apiKey)
 * val vector = embedder.embed("some code", EmbeddingTaskType.RETRIEVAL_DOCUMENT)
 * val vectors = embedder.embedAll(listOf("a", "b"), EmbeddingTaskType.RETRIEVAL_DOCUMENT)
 * ```
 *
 * @param apiKey Gemini API key (from NTIVO_GEMINI_API_KEY env var)
 * @param model Embedding model name (default: gemini-embedding-001)
 * @param defaultTaskType Task type used when calling the no-arg [embed] method
 * @param engine HTTP engine; tests pass a mock, production uses CIO
 */
class GeminiEmbedder(
    private val apiKey: String,
    private val model: String = "gemini-embedding-001",
    private val defaultTaskType: EmbeddingTaskType = EmbeddingTaskType.RETRIEVAL_DOCUMENT,
    engine: HttpClientEngine? = null
) : TaskTypeEmbedder, AutoCloseable {

    private val httpClient = if (engine != null) HttpClient(engine) { configure() } else HttpClient(CIO) { configure() }

    override val maxBatch: Int get() = MAX_BATCH

    private fun HttpClientConfig<*>.configure() {
        install(ContentNegotiation) { json(geminiJson) }
        // Status is checked by hand so error messages never quote the raw body or URL
        expectSuccess = false
    }

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/$model"

    /**
     * Embed text with an explicit task type.
     * Prefer this over the no-arg version for clarity.
     */
    override suspend fun embed(text: String, taskType: EmbeddingTaskType): Vector {
        val response = send("$baseUrl:embedContent", request(text, taskType))
        return Vector(geminiJson.decodeFromString<GeminiEmbedResponse>(response.bodyAsText()).embedding.values)
    }

    /**
     * Embeds [texts] with `batchEmbedContents`, at most [MAX_BATCH] texts per call.
     * Returns one vector per text, in order.
     */
    override suspend fun embedAll(texts: List<String>, taskType: EmbeddingTaskType): List<Vector> =
        texts.chunked(MAX_BATCH).flatMap { chunk ->
            val response = send(
                "$baseUrl:batchEmbedContents",
                GeminiBatchEmbedRequest(chunk.map { request(it, taskType) })
            )
            val embeddings = geminiJson.decodeFromString<GeminiBatchEmbedResponse>(response.bodyAsText()).embeddings
            if (embeddings.size != chunk.size) {
                throw EmbeddingRequestFailed(
                    response.status.value,
                    "Gemini embedding API returned ${embeddings.size} vectors for ${chunk.size} texts"
                )
            }
            embeddings.map { Vector(it.values) }
        }

    /** Embed text using the [defaultTaskType]. Implements Koog's [Embedder] interface. */
    override suspend fun embed(text: String): Vector = embed(text, defaultTaskType)

    /** Cosine distance between two vectors. Implements Koog's [Embedder] interface. */
    override fun diff(embedding1: Vector, embedding2: Vector): Double {
        return 1.0 - embedding1.cosineSimilarity(embedding2)
    }

    override fun close() {
        httpClient.close()
    }

    private fun request(text: String, taskType: EmbeddingTaskType) = GeminiEmbedRequest(
        model = "models/$model",
        content = GeminiContent(parts = listOf(GeminiPart(text))),
        taskType = taskType.name
    )

    /** POSTs [body] to [url] and returns the response when it is 2xx, otherwise throws. */
    private suspend fun send(url: String, body: Any): HttpResponse {
        val response = try {
            httpClient.post(url) {
                header("x-goog-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Transport failures (timeouts, refused connections) quote the URL; keep only the kind
            throw EmbeddingRequestFailed(null, "Could not reach the Gemini embedding API (${e::class.simpleName})", e)
        }
        if (response.status.isSuccess()) return response

        val text = runCatching { response.bodyAsText() }.getOrDefault("")
        val error = runCatching { geminiJson.parseToJsonElement(text) as? JsonObject }.getOrNull()?.get("error") as? JsonObject
        if (response.status == HttpStatusCode.TooManyRequests) {
            throw EmbeddingRateLimited(retryAfterSeconds(response, error))
        }
        val apiMessage = (error?.get("message") as? JsonPrimitive)?.content?.let { clean(it) }
        throw EmbeddingRequestFailed(
            response.status.value,
            "Gemini embedding request failed with HTTP ${response.status.value}" + (apiMessage?.let { ": $it" } ?: "")
        )
    }

    /** The API's error message with URLs, `key=` style query secrets and the key itself removed. */
    private fun clean(message: String): String =
        redactSecrets(message)
            .replace(URL, "<url>")
            .replace(KEY_PARAM, "")
            .replace(apiKey, "REDACTED")
            .take(MAX_ERROR_CHARS)

    /**
     * Seconds to wait from the `Retry-After` header, else from the `retryDelay` (e.g. "38s")
     * of a `google.rpc.RetryInfo` in the error details, else null.
     */
    private fun retryAfterSeconds(response: HttpResponse, error: JsonObject?): Int? {
        response.headers[HttpHeaders.RetryAfter]?.trim()?.toIntOrNull()?.let { return it }
        val details = error?.get("details") as? JsonArray ?: return null
        return details.firstNotNullOfOrNull { detail ->
            ((detail as? JsonObject)?.get("retryDelay") as? JsonPrimitive)?.content
                ?.removeSuffix("s")?.toDoubleOrNull()?.let { ceil(it).toInt() }
        }
    }

    companion object {
        /** The most texts `batchEmbedContents` accepts in one call. */
        const val MAX_BATCH = 100
        private const val MAX_ERROR_CHARS = 500
        private val URL = Regex("https?://\\S+")
        private val KEY_PARAM = Regex("\\bkey=\\S*")
    }
}
