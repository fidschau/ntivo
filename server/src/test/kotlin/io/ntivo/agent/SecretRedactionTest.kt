package io.ntivo.agent

import ai.koog.embeddings.base.Vector
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import io.ntivo.AppContext
import io.ntivo.CodeGraph
import io.ntivo.EmbeddingTaskType
import io.ntivo.EventBus
import io.ntivo.TaskTypeEmbedder
import io.ntivo.VectorStore
import io.ntivo.model.Source
import io.ntivo.model.SourceKind
import io.ntivo.model.SourceState
import io.ntivo.sources.SourceStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ktor puts the full request URL, API key included, into its timeout messages. */
private const val SECRET = "SECRET123"
private const val TIMEOUT_MESSAGE =
    "Request timeout has expired [url=https://x/y?key=$SECRET, request_timeout=15000 ms]"

/** Fails like a Gemini embed call that timed out. */
private class TimingOutEmbedder : TaskTypeEmbedder {
    override suspend fun embed(text: String, taskType: EmbeddingTaskType): Vector =
        throw IllegalStateException(TIMEOUT_MESSAGE)
    override suspend fun embed(text: String): Vector = throw IllegalStateException(TIMEOUT_MESSAGE)
    override fun diff(embedding1: Vector, embedding2: Vector): Double = 0.0
}

/** Secrets in exception messages never reach the trace file, the trace response or an error body. */
class SecretRedactionTest {

    private val dir: File = Files.createTempDirectory("redaction").toFile()
    private val ctx = AppContext(
        sources = SourceStore(File(dir, "sources.json")),
        events = EventBus(),
        vectorStore = VectorStore(),
        codeGraph = CodeGraph(),
        embedder = null,
        executor = null,
        dataDir = dir
    )
    private val collection = "test_redaction_${UUID.randomUUID().toString().take(8)}"
    private var qdrantUsed = false

    @AfterTest
    fun tearDown() {
        if (qdrantUsed) runCatching { runBlocking { withTimeout(3_000) { ctx.vectorStore.deleteCollection(collection) } } }
        ctx.close()
        dir.deleteRecursively()
    }

    private val android = Source("s1", "android-app", SourceKind.github, SourceState.ready, languages = listOf("Kotlin"))

    /** Calls the real search tool with an embedder that times out, then answers. */
    private inner class SearchingRunner : AgentRunner {
        override suspend fun run(systemPrompt: String, question: String, readySourceIds: List<String>, trace: TraceRecorder): String {
            CodeSearchTools(TimingOutEmbedder(), ctx.vectorStore, collection, readySourceIds, trace).searchCodebase(question)
            return """{"verdict": null, "prose": "The code could not be searched."}"""
        }
        override suspend fun repair(rawOutput: String): String = rawOutput
    }

    /** A tool body that throws straight through [TraceRecorder.record]. */
    private class ThrowingToolRunner : AgentRunner {
        override suspend fun run(systemPrompt: String, question: String, readySourceIds: List<String>, trace: TraceRecorder): String {
            runCatching { trace.record<String>("callersOf", "load") { throw IllegalStateException(TIMEOUT_MESSAGE) } }
            return """{"verdict": null, "prose": "Could not follow connections."}"""
        }
        override suspend fun repair(rawOutput: String): String = rawOutput
    }

    private fun assumeQdrant() {
        val reachable = runCatching {
            runBlocking { withTimeout(3_000) { ctx.vectorStore.ensureCollection(collection) } }
        }.isSuccess
        assumeTrue("Qdrant not reachable on localhost:6334", reachable)
        qdrantUsed = true
    }

    private fun assertTraceIsClean(runner: AgentRunner, expectedTool: String) = testApplication {
        ctx.sources.put(android)
        application {
            install(ContentNegotiation) { json() }
            configureAskRouting(ctx, AnswerService(ctx, { null }, runner)) { null }
        }

        val answer = client.post("/api/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": "What is the friend limit?"}""")
        }
        assertEquals(HttpStatusCode.OK, answer.status)
        val answerId = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(answer.bodyAsText())!!.groupValues[1]

        val saved = File(ctx.tracesDir, "$answerId.json").readText()
        assertTrue(saved.contains(expectedTool), "the failed call is still recorded")
        assertTrue(saved.contains("REDACTED"))
        assertFalse(saved.contains(SECRET), "trace file leaks the key: $saved")

        val trace = client.get("/api/answers/$answerId/trace")
        assertEquals(HttpStatusCode.OK, trace.status)
        assertFalse(trace.bodyAsText().contains(SECRET), "trace response leaks the key")
    }

    @Test
    fun `a timed out embed call does not leak the key into the trace`() {
        assumeQdrant()
        assertTraceIsClean(SearchingRunner(), "searchCodebase")
    }

    @Test
    fun `a throwing tool call does not leak the key into the trace`() {
        assertTraceIsClean(ThrowingToolRunner(), "callersOf")
    }

    @Test
    fun `a failing answer does not leak the key into the error body`() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureAskRouting(ctx, { _, _ -> throw IllegalStateException(TIMEOUT_MESSAGE) }) { null }
        }
        val response = client.post("/api/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": "Why?"}""")
        }
        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = response.bodyAsText()
        assertFalse(body.contains(SECRET), "502 body leaks the key: $body")
        assertTrue(body.contains("timeout"), "the useful part of the message is kept: $body")
    }

    @Test
    fun `safeMessage redacts key, token and access_token query parameters`() {
        val text = safeMessage(
            IllegalStateException("GET https://a/b?key=AIza-1&x=2&token=t0k&access_token=at]] done")
        )
        assertEquals("GET https://a/b?key=REDACTED&x=2&token=REDACTED&access_token=REDACTED]] done", text)
        assertEquals("IllegalStateException", safeMessage(IllegalStateException()))
    }
}
