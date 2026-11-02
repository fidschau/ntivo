package io.ntivo

import ai.koog.embeddings.base.Vector
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ntivo.agent.MIN_SIMILARITY
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class OllamaEmbedRequest(val model: String, val input: List<String>)

@Serializable
private data class OllamaEmbedResponse(val embeddings: List<List<Double>> = emptyList())

@Serializable
private data class OllamaError(val error: String? = null)

private val ollamaJson = Json { ignoreUnknownKeys = true }

/**
 * Embedder that calls a local Ollama server (`POST /api/embed`), so code never leaves
 * the machine and there is no quota.
 *
 * Ollama has no task type parameter. Models trained with instructions expect the task in
 * the text instead, so [instructionFor] adds the prefix the model family was trained on:
 * Qwen3 embeddings put an instruction on queries only, nomic-embed-text prefixes both
 * sides. Documents and queries must be embedded the same way every time, so changing the
 * prefixes means re-indexing.
 *
 * Errors use the same exceptions as [GeminiEmbedder]: a model that is not pulled is an
 * [EmbeddingRequestFailed] with status 404 (not retried), an unreachable server has a null
 * status (retried).
 *
 * @param model Ollama model name, e.g. `qwen3-embedding:0.6b`
 * @param baseUrl Ollama server, default `http://localhost:11434`
 * @param engine HTTP engine; tests pass a mock, production uses CIO
 */
class OllamaEmbedder(
    val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_URL,
    private val defaultTaskType: EmbeddingTaskType = EmbeddingTaskType.RETRIEVAL_DOCUMENT,
    engine: HttpClientEngine? = null
) : TaskTypeEmbedder, AutoCloseable {

    private val httpClient = if (engine != null) {
        HttpClient(engine) { configure() }
    } else {
        // The first call loads the model, and a batch of long pieces takes a while on CPU
        HttpClient(CIO) { engine { requestTimeout = REQUEST_TIMEOUT_MS }; configure() }
    }

    private fun HttpClientConfig<*>.configure() {
        install(ContentNegotiation) { json(ollamaJson) }
        expectSuccess = false
    }

    override val maxBatch: Int get() = MAX_BATCH

    override val minSimilarity: Float get() = minSimilarityFor(model)

    override suspend fun embed(text: String, taskType: EmbeddingTaskType): Vector =
        embedAll(listOf(text), taskType).single()

    /** Embeds [texts] in calls of at most [MAX_BATCH], returning one vector per text, in order. */
    override suspend fun embedAll(texts: List<String>, taskType: EmbeddingTaskType): List<Vector> {
        val prefix = instructionFor(model, taskType)
        return texts.chunked(MAX_BATCH).flatMap { chunk ->
            val embeddings = send(chunk.map { prefix + it })
            if (embeddings.size != chunk.size) {
                throw EmbeddingRequestFailed(200, "Ollama returned ${embeddings.size} vectors for ${chunk.size} texts")
            }
            embeddings.map { Vector(it) }
        }
    }

    override suspend fun embed(text: String): Vector = embed(text, defaultTaskType)

    override fun diff(embedding1: Vector, embedding2: Vector): Double =
        1.0 - embedding1.cosineSimilarity(embedding2)

    override fun close() {
        httpClient.close()
    }

    private suspend fun send(input: List<String>): List<List<Double>> {
        val response = try {
            httpClient.post("$baseUrl/api/embed") {
                contentType(ContentType.Application.Json)
                setBody(OllamaEmbedRequest(model, input))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw EmbeddingRequestFailed(
                null,
                "Could not reach Ollama at $baseUrl (${e::class.simpleName}). Start it with `brew services start ollama`.",
                e
            )
        }
        val text = response.bodyAsText()
        if (response.status.isSuccess()) {
            return ollamaJson.decodeFromString<OllamaEmbedResponse>(text).embeddings
        }
        if (response.status == HttpStatusCode.NotFound) {
            throw EmbeddingRequestFailed(404, "Ollama does not have the model '$model'. Run `ollama pull $model`.")
        }
        val detail = runCatching { ollamaJson.decodeFromString<OllamaError>(text).error }.getOrNull()
        throw EmbeddingRequestFailed(
            response.status.value,
            "Ollama embedding request failed with HTTP ${response.status.value}" +
                (detail?.let { ": ${it.take(MAX_ERROR_CHARS)}" } ?: "")
        )
    }

    companion object {
        const val DEFAULT_MODEL = "qwen3-embedding:0.6b"
        const val DEFAULT_URL = "http://localhost:11434"

        /** Texts per `/api/embed` call: small enough to report progress often. */
        const val MAX_BATCH = 32
        private const val REQUEST_TIMEOUT_MS = 300_000L
        private const val MAX_ERROR_CHARS = 500

        /**
         * The search cut-off for a model family. Qwen3 scores run lower than Gemini's: on
         * timber, right answers scored 0.53 to 0.71 and an unrelated question at most 0.37.
         * Other models keep the Gemini default until the eval calibrates them.
         */
        fun minSimilarityFor(model: String): Float =
            if (model.lowercase().startsWith("qwen3-embedding")) 0.45f else MIN_SIMILARITY

        /**
         * The text prefix that stands in for a task type, by model family. Documents get
         * none for Qwen3, so the stored text is the code itself.
         */
        fun instructionFor(model: String, taskType: EmbeddingTaskType): String {
            val family = model.substringBefore(':').lowercase()
            return when {
                family.startsWith("qwen3-embedding") -> when (taskType) {
                    EmbeddingTaskType.RETRIEVAL_DOCUMENT -> ""
                    EmbeddingTaskType.CODE_RETRIEVAL_QUERY ->
                        "Instruct: Given a question about a codebase, retrieve the code that answers it\nQuery:"
                    EmbeddingTaskType.RETRIEVAL_QUERY ->
                        "Instruct: Given a question, retrieve the passages that answer it\nQuery:"
                    EmbeddingTaskType.SEMANTIC_SIMILARITY -> "Instruct: Retrieve semantically similar text\nQuery:"
                    EmbeddingTaskType.CLASSIFICATION -> "Instruct: Classify the given text\nQuery:"
                    EmbeddingTaskType.CLUSTERING -> "Instruct: Identify the topic of the given text\nQuery:"
                }
                family.startsWith("nomic-embed-text") -> when (taskType) {
                    EmbeddingTaskType.RETRIEVAL_DOCUMENT -> "search_document: "
                    EmbeddingTaskType.RETRIEVAL_QUERY, EmbeddingTaskType.CODE_RETRIEVAL_QUERY -> "search_query: "
                    EmbeddingTaskType.SEMANTIC_SIMILARITY, EmbeddingTaskType.CLASSIFICATION -> "classification: "
                    EmbeddingTaskType.CLUSTERING -> "clustering: "
                }
                else -> ""
            }
        }
    }
}
