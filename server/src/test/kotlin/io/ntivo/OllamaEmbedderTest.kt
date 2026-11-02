package io.ntivo

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [OllamaEmbedder] against a Ktor [MockEngine]: no Ollama needed. */
class OllamaEmbedderTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class Recorded(val url: String, val body: JsonObject)

    private fun embedder(
        model: String = "qwen3-embedding:0.6b",
        recorded: MutableList<Recorded> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(JsonObject) -> HttpResponseData
    ) = OllamaEmbedder(
        model = model,
        baseUrl = "http://ollama.test:11434",
        engine = MockEngine { request: HttpRequestData ->
            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            recorded += Recorded(request.url.toString(), body)
            handler(body)
        }
    )

    /** One 2-dimensional vector per input, value i for the i-th text. */
    private fun MockRequestHandleScope.respondFor(body: JsonObject): HttpResponseData {
        val n = body["input"]!!.jsonArray.size
        return respond(
            """{"model":"m","embeddings":[${(0 until n).joinToString(",") { "[$it.0, 0.5]" }}]}""",
            HttpStatusCode.OK,
            jsonHeaders
        )
    }

    private fun JsonObject.inputs() = this["input"]!!.jsonArray.map { it.jsonPrimitive.content }

    @Test
    fun `embedAll posts to api embed with the model and returns vectors in order`(): Unit = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val e = embedder(recorded = recorded) { respondFor(it) }

        val vectors = e.embedAll(listOf("a", "b", "c"), EmbeddingTaskType.RETRIEVAL_DOCUMENT)

        assertEquals(listOf(listOf(0.0, 0.5), listOf(1.0, 0.5), listOf(2.0, 0.5)), vectors.map { it.values })
        assertEquals(1, recorded.size)
        assertEquals("http://ollama.test:11434/api/embed", recorded.single().url)
        assertEquals("qwen3-embedding:0.6b", recorded.single().body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `embedAll splits into calls of at most MAX_BATCH`(): Unit = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val e = embedder(recorded = recorded) { respondFor(it) }

        val vectors = e.embedAll(List(OllamaEmbedder.MAX_BATCH + 5) { "t$it" }, EmbeddingTaskType.RETRIEVAL_DOCUMENT)

        assertEquals(OllamaEmbedder.MAX_BATCH + 5, vectors.size)
        assertEquals(listOf(OllamaEmbedder.MAX_BATCH, 5), recorded.map { it.body.inputs().size })
    }

    @Test
    fun `qwen3 documents are sent as is and code queries carry an instruction`(): Unit = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val e = embedder(recorded = recorded) { respondFor(it) }

        e.embed("fun invite() {}", EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        e.embed("friend limit", EmbeddingTaskType.CODE_RETRIEVAL_QUERY)

        assertEquals("fun invite() {}", recorded[0].body.inputs().single())
        val query = recorded[1].body.inputs().single()
        assertTrue(query.startsWith("Instruct: "), query)
        assertTrue(query.endsWith("\nQuery:friend limit"), query)
    }

    @Test
    fun `nomic prefixes both documents and queries`() {
        assertEquals("search_document: ", OllamaEmbedder.instructionFor("nomic-embed-text", EmbeddingTaskType.RETRIEVAL_DOCUMENT))
        assertEquals("search_query: ", OllamaEmbedder.instructionFor("nomic-embed-text:v1.5", EmbeddingTaskType.CODE_RETRIEVAL_QUERY))
        assertEquals("", OllamaEmbedder.instructionFor("mxbai-embed-large", EmbeddingTaskType.CODE_RETRIEVAL_QUERY))
    }

    @Test
    fun `Qwen3 gets its own similarity cut-off, other models the default`() {
        assertEquals(0.45f, OllamaEmbedder("qwen3-embedding:0.6b").minSimilarity)
        assertEquals(0.45f, OllamaEmbedder.minSimilarityFor("qwen3-embedding:4b"))
        assertEquals(io.ntivo.agent.MIN_SIMILARITY, OllamaEmbedder.minSimilarityFor("nomic-embed-text"))
    }

    @Test
    fun `a model that is not pulled fails with 404 and says how to pull it`(): Unit = runBlocking {
        val e = embedder { respond("""{"error":"model \"x\" not found, try pulling it first"}""", HttpStatusCode.NotFound, jsonHeaders) }

        val error = assertFailsWith<EmbeddingRequestFailed> { e.embed("a", EmbeddingTaskType.RETRIEVAL_DOCUMENT) }

        assertEquals(404, error.status)
        assertTrue(error.message!!.contains("ollama pull qwen3-embedding:0.6b"), error.message)
    }

    @Test
    fun `other errors carry the status and Ollama's message`(): Unit = runBlocking {
        val e = embedder { respond("""{"error":"out of memory"}""", HttpStatusCode.InternalServerError, jsonHeaders) }

        val error = assertFailsWith<EmbeddingRequestFailed> { e.embed("a", EmbeddingTaskType.RETRIEVAL_DOCUMENT) }

        assertEquals(500, error.status)
        assertTrue(error.message!!.endsWith("HTTP 500: out of memory"), error.message)
    }

    @Test
    fun `an unreachable server has no status so the indexer retries it`(): Unit = runBlocking {
        val e = embedder { throw IOException("Connection refused") }

        val error = assertFailsWith<EmbeddingRequestFailed> { e.embed("a", EmbeddingTaskType.RETRIEVAL_DOCUMENT) }

        assertNull(error.status)
        assertTrue(error.message!!.contains("brew services start ollama"), error.message)
    }

    @Test
    fun `a wrong number of vectors is an error, not a silent mismatch`(): Unit = runBlocking {
        val e = embedder { respond("""{"embeddings":[[1.0]]}""", HttpStatusCode.OK, jsonHeaders) }

        assertFailsWith<EmbeddingRequestFailed> { e.embedAll(listOf("a", "b"), EmbeddingTaskType.RETRIEVAL_DOCUMENT) }
    }
}
