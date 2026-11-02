package io.ntivo.agent

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
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ntivo.AppContext
import io.ntivo.CodeGraph
import io.ntivo.EventBus
import io.ntivo.VectorStore
import io.ntivo.model.Answer
import io.ntivo.model.Piece
import io.ntivo.model.Trace
import io.ntivo.sources.SourceStore
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AskRoutingTest {

    private val dir: File = Files.createTempDirectory("ask").toFile()
    private val ctx = AppContext(
        sources = SourceStore(File(dir, "sources.json")),
        events = EventBus(),
        vectorStore = VectorStore(),
        codeGraph = CodeGraph(),
        embedder = null,
        executor = null,
        dataDir = dir
    )

    @AfterTest
    fun tearDown() {
        ctx.close()
        dir.deleteRecursively()
    }

    private val piece = Piece(
        "11111111-1111-1111-1111-111111111111", "s1", "android-app", "Kotlin",
        "invite/InviteRepository.kt", "friendLimit", "InviteRepository.friendLimit", 40, 46, "fun friendLimit() = 5"
    )

    private val asked = mutableListOf<Pair<String, List<String>?>>()
    private val stub = QuestionAnswerer { question, sourceIds ->
        asked += question to sourceIds
        Answer(id = "a-1", question = question, prose = "Android allows 5.", durationMs = 12)
    }

    private fun ApplicationTestBuilder.setUp(answerer: QuestionAnswerer? = stub) {
        application {
            install(ContentNegotiation) { json() }
            configureAskRouting(ctx, answerer) { id -> piece.takeIf { it.id == id } }
        }
    }

    @Test
    fun `ask returns the answer`() = testApplication {
        setUp()
        val response = client.post("/api/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": "What is the friend limit?", "sourceIds": ["s1"]}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val answer = Json.decodeFromString(Answer.serializer(), response.bodyAsText())
        assertEquals("Android allows 5.", answer.prose)
        assertEquals<List<Pair<String, List<String>?>>>(listOf("What is the friend limit?" to listOf("s1")), asked)
    }

    @Test
    fun `blank question is a bad request`() = testApplication {
        setUp()
        val response = client.post("/api/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": "   "}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `malformed body is a bad request`() = testApplication {
        setUp()
        val response = client.post("/api/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"nope": 1}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `no model key is service unavailable`() = testApplication {
        setUp(answerer = null)
        val response = client.post("/api/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": "Why?"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("NTIVO_ANTHROPIC_API_KEY"))
    }

    @Test
    fun `a failing answer is a bad gateway`() = testApplication {
        setUp(answerer = { _, _ -> error("model down") })
        val response = client.post("/api/ask") {
            contentType(ContentType.Application.Json)
            setBody("""{"question": "Why?"}""")
        }
        assertEquals(HttpStatusCode.BadGateway, response.status)
    }

    @Test
    fun `missing trace is not found`() = testApplication {
        setUp()
        assertEquals(HttpStatusCode.NotFound, client.get("/api/answers/does-not-exist/trace").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/answers/..%2Fsources/trace").status)
    }

    @Test
    fun `trace is served after it was written`() = testApplication {
        setUp()
        val trace = Trace("a-2", "explained", emptyList(), emptyList(), listOf("ios-app (failed)"), emptyList(), emptyList(), "gemini-2.5-flash")
        AnswerStore(ctx.tracesDir).save(AnswerRecord(Answer("a-2", "q", prose = "p", durationMs = 1), trace))

        val response = client.get("/api/answers/a-2/trace")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(trace, Json.decodeFromString(Trace.serializer(), response.bodyAsText()))
    }

    @Test
    fun `piece is served or not found`() = testApplication {
        setUp()
        val ok = client.get("/api/pieces/${piece.id}")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(piece, Json.decodeFromString(Piece.serializer(), ok.bodyAsText()))

        assertEquals(HttpStatusCode.NotFound, client.get("/api/pieces/22222222-2222-2222-2222-222222222222").status)
    }
}
