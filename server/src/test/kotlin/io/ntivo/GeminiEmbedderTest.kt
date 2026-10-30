package io.ntivo

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [GeminiEmbedder] against a Ktor [MockEngine]: no network, no quota. */
class GeminiEmbedderTest {

    private val apiKey = "test-secret-key-123"
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private class Recorded(val url: String, val apiKeyHeader: String?, val body: JsonObject)

    private fun embedder(
        recorded: MutableList<Recorded> = mutableListOf(),
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> io.ktor.client.request.HttpResponseData
    ) = GeminiEmbedder(
        apiKey = apiKey,
        engine = MockEngine { request ->
            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            recorded += Recorded(request.url.toString(), request.headers["x-goog-api-key"], body)
            handler(request)
        }
    )

    /** A batch response with one 2-dimensional vector per request, value i for the i-th text. */
    private fun batchResponseFor(body: JsonObject): String {
        val n = body["requests"]!!.jsonArray.size
        return """{"embeddings":[${(0 until n).joinToString(",") { """{"values":[$it.0, 0.5]}""" }}]}"""
    }

    @Test
    fun `embedAll sends one batchEmbedContents call with the task type on every request`(): Unit = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val e = embedder(recorded) { request ->
            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            respond(batchResponseFor(body), HttpStatusCode.OK, jsonHeaders)
        }

        val vectors = e.embedAll(listOf("a", "b", "c"), EmbeddingTaskType.RETRIEVAL_DOCUMENT)

        assertEquals(listOf(listOf(0.0, 0.5), listOf(1.0, 0.5), listOf(2.0, 0.5)), vectors.map { it.values })
        assertEquals(1, recorded.size)
        val call = recorded.single()
        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-embedding-001:batchEmbedContents",
            call.url
        )
        // The key travels in a header, never in the URL
        assertFalse("key=" in call.url)
        assertEquals(apiKey, call.apiKeyHeader)
        val requests = call.body["requests"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("a", "b", "c"), requests.map {
            it["content"]!!.jsonObject["parts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        })
        assertTrue(requests.all { it["taskType"]!!.jsonPrimitive.content == "RETRIEVAL_DOCUMENT" })
        assertTrue(requests.all { it["model"]!!.jsonPrimitive.content == "models/gemini-embedding-001" })
    }

    @Test
    fun `embedAll splits more than 100 texts into calls of at most 100`(): Unit = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val e = embedder(recorded) { request ->
            val body = Json.parseToJsonElement(String(request.body.toByteArray())).jsonObject
            respond(batchResponseFor(body), HttpStatusCode.OK, jsonHeaders)
        }

        val vectors = e.embedAll((1..250).map { "t$it" }, EmbeddingTaskType.RETRIEVAL_DOCUMENT)

        assertEquals(250, vectors.size)
        assertEquals(listOf(100, 100, 50), recorded.map { it.body["requests"]!!.jsonArray.size })
    }

    @Test
    fun `embedAll of nothing makes no call`(): Unit = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val e = embedder(recorded) { error("no call expected") }
        assertEquals(emptyList(), e.embedAll(emptyList(), EmbeddingTaskType.RETRIEVAL_DOCUMENT))
        assertEquals(0, recorded.size)
    }

    @Test
    fun `a 429 throws EmbeddingRateLimited with Retry-After and names the free tier limit`(): Unit = runBlocking {
        val e = embedder {
            respond(
                """{"error":{"code":429,"message":"Resource has been exhausted (e.g. check quota).","status":"RESOURCE_EXHAUSTED"}}""",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("17"))
            )
        }

        val thrown = assertFailsWith<EmbeddingRateLimited> { e.embedAll(listOf("a"), EmbeddingTaskType.RETRIEVAL_DOCUMENT) }
        assertEquals(17, thrown.retryAfterSeconds)
        val message = thrown.message!!
        assertTrue("100" in message && "per minute" in message, message)
        assertFalse(apiKey in message)
        assertFalse("key=" in message)
        assertFalse("googleapis.com" in message)
    }

    @Test
    fun `a 429 without Retry-After falls back to the retry delay in the error details`(): Unit = runBlocking {
        val e = embedder {
            respond(
                """{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED",
                   "details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"38s"}]}}""",
                HttpStatusCode.TooManyRequests,
                jsonHeaders
            )
        }
        val thrown = assertFailsWith<EmbeddingRateLimited> { e.embed("a", EmbeddingTaskType.RETRIEVAL_DOCUMENT) }
        assertEquals(38, thrown.retryAfterSeconds)
    }

    @Test
    fun `a 429 with no retry hint has a null retryAfterSeconds`(): Unit = runBlocking {
        val e = embedder { respond("Too many", HttpStatusCode.TooManyRequests) }
        val thrown = assertFailsWith<EmbeddingRateLimited> { e.embed("a", EmbeddingTaskType.RETRIEVAL_DOCUMENT) }
        assertNull(thrown.retryAfterSeconds)
    }

    @Test
    fun `a 500 reports status and the API message without the key, the URL or the raw body`(): Unit = runBlocking {
        val e = embedder {
            respond(
                """{"error":{"code":500,"message":"Internal error calling https://x.test/v1?key=$apiKey&alt=json",
                   "status":"INTERNAL"},"debug":"raw-body-marker key=$apiKey"}""",
                HttpStatusCode.InternalServerError,
                jsonHeaders
            )
        }

        val thrown = assertFailsWith<EmbeddingRequestFailed> { e.embedAll(listOf("a"), EmbeddingTaskType.RETRIEVAL_DOCUMENT) }
        val message = thrown.message!!
        assertEquals(500, thrown.status)
        assertTrue("500" in message, message)
        assertTrue("Internal error calling" in message, message)
        assertFalse("key=" in message, message)
        assertFalse(apiKey in message, message)
        assertFalse("raw-body-marker" in message, message)
        assertFalse("generativelanguage.googleapis.com" in message, message)
    }

    @Test
    fun `a non-JSON error body is not echoed`(): Unit = runBlocking {
        val e = embedder { respond("<html>key=$apiKey</html>", HttpStatusCode.BadGateway) }
        val thrown = assertFailsWith<EmbeddingRequestFailed> { e.embed("a", EmbeddingTaskType.RETRIEVAL_DOCUMENT) }
        assertTrue("502" in thrown.message!!, thrown.message)
        assertFalse("key=" in thrown.message!!)
        assertFalse("<html>" in thrown.message!!)
    }

    @Test
    fun `a batch response with the wrong number of vectors fails`(): Unit = runBlocking {
        val e = embedder { respond("""{"embeddings":[{"values":[1.0]}]}""", HttpStatusCode.OK, jsonHeaders) }
        assertFailsWith<EmbeddingRequestFailed> { e.embedAll(listOf("a", "b"), EmbeddingTaskType.RETRIEVAL_DOCUMENT) }
    }

    @Test
    fun `single embed uses embedContent and checks the status`(): Unit = runBlocking {
        val recorded = mutableListOf<Recorded>()
        val e = embedder(recorded) { respond("""{"embedding":{"values":[0.25, 0.75]}}""", HttpStatusCode.OK, jsonHeaders) }
        assertEquals(listOf(0.25, 0.75), e.embed("hello", EmbeddingTaskType.CODE_RETRIEVAL_QUERY).values)
        val call = recorded.single()
        assertTrue(call.url.endsWith("/models/gemini-embedding-001:embedContent"), call.url)
        assertEquals("CODE_RETRIEVAL_QUERY", call.body["taskType"]!!.jsonPrimitive.content)
    }
}

class TaskTypeEmbedderDefaultTest {
    @Test
    fun `the default embedAll embeds each text in order with the given task type`(): Unit = runBlocking {
        val seen = mutableListOf<Pair<String, EmbeddingTaskType>>()
        val embedder = object : TaskTypeEmbedder {
            override suspend fun embed(text: String, taskType: EmbeddingTaskType): ai.koog.embeddings.base.Vector {
                seen += text to taskType
                return ai.koog.embeddings.base.Vector(listOf(text.length.toDouble()))
            }
            override suspend fun embed(text: String) = embed(text, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
            override fun diff(embedding1: ai.koog.embeddings.base.Vector, embedding2: ai.koog.embeddings.base.Vector) = 0.0
        }
        val vectors = embedder.embedAll(listOf("a", "bb"), EmbeddingTaskType.SEMANTIC_SIMILARITY)
        assertEquals(listOf(listOf(1.0), listOf(2.0)), vectors.map { it.values })
        assertEquals(listOf("a" to EmbeddingTaskType.SEMANTIC_SIMILARITY, "bb" to EmbeddingTaskType.SEMANTIC_SIMILARITY), seen)
    }
}
