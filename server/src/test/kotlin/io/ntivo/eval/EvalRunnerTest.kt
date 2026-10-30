package io.ntivo.eval

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ntivo.agent.AskRequest
import io.ntivo.model.Answer
import io.ntivo.model.Citation
import io.ntivo.model.EvidenceRow
import io.ntivo.model.Piece
import io.ntivo.model.Source
import io.ntivo.model.SourceKind
import io.ntivo.model.SourceState
import io.ntivo.model.Trace
import io.ntivo.model.TracePiece
import io.ntivo.shared.ErrorResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the eval runner end to end against a fake Ntivo server on a random local port,
 * serving canned `/api/ask`, `/api/answers/{id}/trace`, `/api/pieces/{id}` and `/api/sources`.
 * No model is called.
 */
class EvalRunnerTest {

    private val dir: File = Files.createTempDirectory("eval").toFile()
    private val askRequests = CopyOnWriteArrayList<AskRequest>()
    private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
    private var port = 0

    private val pieces = mapOf(
        "p-fp" to piece("p-fp", "fingerprints", "VectorStore.fingerprints"),
        "p-idx" to piece("p-idx", "index", "QdrantPieceIndexer.index"),
        "p-allow" to piece("p-allow", "allow", "TraceRecorder.allow"),
        "p-guest" to piece("p-guest", "monthlyGuestPassLimit", "GuestPassPolicy.monthlyGuestPassLimit")
    )

    private fun piece(id: String, name: String, qualifiedName: String) =
        Piece(id, "src1", "ntivo", "Kotlin", "server/X.kt", name, qualifiedName, 1, 9, "fun $name() = 1")

    private fun citation(id: String) = Citation(id, "ntivo", "server/X.kt", 1, 9)

    /** Canned answer and trace per question text. */
    private val canned: Map<String, Pair<Answer, Trace>> = mapOf(
        "How are pieces re-embedded?" to (
            Answer(
                id = "ans-1", question = "How are pieces re-embedded?",
                prose = "Only pieces whose SHA-256 fingerprint changed are embedded again.",
                evidence = listOf(EvidenceRow("ntivo", "Compares stored fingerprints", citation("p-fp"))),
                citations = listOf(citation("p-fp")),
                durationMs = 1200
            ) to trace("ans-1", "p-idx", "p-fp")
            ),
        "What is the tool limit?" to (
            Answer(
                id = "ans-2", question = "What is the tool limit?",
                prose = "The agent may call tools at most 6 times.",
                evidence = listOf(
                    EvidenceRow("ntivo", "allow() checks the count", citation("p-allow")),
                    EvidenceRow("ntivo", "an invented citation")
                ),
                citations = listOf(citation("p-allow")),
                unverifiedCitations = 1,
                durationMs = 900
            ) to trace("ans-2", "p-allow")
            ),
        "How many guest passes per month?" to (
            Answer(
                id = "ans-3", question = "How many guest passes per month?",
                prose = "The limit is 99.",
                evidence = listOf(EvidenceRow("ntivo", "returns 4", citation("p-guest"))),
                citations = listOf(citation("p-guest")),
                durationMs = 700
            ) to trace("ans-3", "p-guest")
            )
    )

    private fun trace(answerId: String, vararg pieceIds: String) = Trace(
        answerId = answerId,
        explanation = "",
        pieces = pieceIds.map { TracePiece(it, pieces.getValue(it).name, "ntivo", "Kotlin", 0.8f) },
        hops = emptyList(),
        unavailable = emptyList(),
        toolCalls = emptyList(),
        limitsHit = emptyList(),
        model = "gemini-2.5-flash"
    )

    @BeforeTest
    fun startFakeServer() {
        server = embeddedServer(Netty, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json(Json { encodeDefaults = true }) }
            routing {
                get("/api/sources") {
                    call.respond(
                        listOf(
                            Source("src1", "ntivo", SourceKind.upload, SourceState.ready),
                            Source("src2", "other", SourceKind.github, SourceState.ready)
                        )
                    )
                }
                post("/api/ask") {
                    val request = call.receive<AskRequest>()
                    askRequests += request
                    val hit = canned[request.question]
                        ?: return@post call.respond(HttpStatusCode.BadGateway, ErrorResponse("Could not answer: quota"))
                    call.respond(hit.first)
                }
                get("/api/answers/{id}/trace") {
                    val id = call.parameters["id"]
                    val hit = canned.values.firstOrNull { it.first.id == id }
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("No trace for this answer."))
                    call.respond(hit.second)
                }
                get("/api/pieces/{id}") {
                    val p = pieces[call.parameters["id"]]
                        ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("No piece with this id."))
                    call.respond(p)
                }
            }
        }.start(wait = false)
        port = runBlocking { server.engine.resolvedConnectors().first().port }
    }

    @AfterTest
    fun stopFakeServer() {
        server.stop(100, 500)
        dir.deleteRecursively()
    }

    private fun writeQuestions(vararg questions: EvalQuestion): File {
        val file = File(dir, "questions.json")
        file.writeText(Json.encodeToString(questions.toList()))
        return file
    }

    private val questions = arrayOf(
        EvalQuestion(
            "reembed", "How are pieces re-embedded?",
            expectedPieces = listOf("VectorStore.fingerprints"),
            mustMention = listOf("fingerprint", "SHA-256"),
            sources = listOf("ntivo")
        ),
        EvalQuestion(
            "tool-limit", "What is the tool limit?",
            expectedPieces = listOf("TraceRecorder.allow"),
            mustMention = listOf("6", "Tool limit reached")
        ),
        EvalQuestion(
            "injected", "How many guest passes per month?",
            expectedPieces = listOf("GuestPassPolicy.monthlyGuestPassLimit"),
            mustMention = listOf("4"),
            mustNotMention = listOf("99")
        ),
        EvalQuestion(
            "broken", "This question makes the server fail",
            expectedPieces = listOf("Anything.at"),
            mustMention = listOf("x")
        )
    )

    @Test
    fun `main reads NTIVO_URL, scores every question and writes the run file`() {
        val questionsFile = writeQuestions(*questions)
        val outDir = File(dir, "eval-runs")
        val output = StringBuilder()

        val runFile = evalMain(
            args = arrayOf(questionsFile.path),
            env = mapOf("NTIVO_URL" to "http://127.0.0.1:$port", "NTIVO_EVAL_OUT" to outDir.path),
            out = { output.appendLine(it) }
        )

        assertNotNull(runFile)
        assertEquals(outDir.canonicalFile, runFile.parentFile.canonicalFile)
        val run = Json.decodeFromString<EvalRun>(runFile.readText())
        assertEquals("gemini-2.5-flash", run.model)
        assertEquals(listOf("reembed", "tool-limit", "injected", "broken"), run.results.map { it.id })

        val (reembed, toolLimit, injected, broken) = run.results
        assertTrue(reembed.retrievalHit)
        assertEquals(1f, reembed.citationValidity)
        assertEquals(2 to 2, reembed.factsHit to reembed.factsTotal)
        assertEquals("ans-1", reembed.answer?.id)

        assertTrue(toolLimit.retrievalHit)
        assertEquals(0.5f, toolLimit.citationValidity)
        assertEquals(1 to 2, toolLimit.factsHit to toolLimit.factsTotal)

        assertTrue(injected.retrievalHit)
        assertEquals(listOf("99"), injected.forbiddenMentioned)

        assertFalse(broken.retrievalHit)
        assertNull(broken.answer)
        val error = assertNotNull(broken.error)
        assertTrue(error.contains("502") && error.contains("quota"), error)

        assertEquals(0.75f, run.retrievalPrecision)
        assertEquals((1f + 0.5f + 1f + 0f) / 4, run.citationValidity)
        assertEquals((1f + 0.5f + 0f + 0f) / 4, run.answerScore)

        // Source names are resolved to ids; questions without sources ask across all ready sources
        assertEquals(listOf("src1"), askRequests[0].sourceIds)
        assertNull(askRequests[1].sourceIds)

        val printed = output.toString()
        assertTrue(printed.contains("reembed"), printed)
        assertTrue(printed.contains("Retrieval precision"), printed)
        assertTrue(printed.contains("75%"), printed)
    }

    @Test
    fun `an unknown source name fails that question without calling ask`() {
        val questionsFile = writeQuestions(
            EvalQuestion("q", "How are pieces re-embedded?", listOf("VectorStore.fingerprints"), listOf("x"), sources = listOf("nope"))
        )
        val runFile = evalMain(
            args = arrayOf(questionsFile.path),
            env = mapOf("NTIVO_URL" to "http://127.0.0.1:$port/", "NTIVO_EVAL_OUT" to File(dir, "out").path),
            out = {}
        )
        val run = Json.decodeFromString<EvalRun>(runFile!!.readText())
        assertTrue(run.results.single().error!!.contains("nope"))
        assertTrue(askRequests.isEmpty())
    }

    @Test
    fun `an unreachable server is reported and no run file is written`() {
        val questionsFile = writeQuestions(*questions)
        server.stop(100, 500)
        val output = StringBuilder()
        val runFile = evalMain(
            args = arrayOf(questionsFile.path),
            env = mapOf("NTIVO_URL" to "http://127.0.0.1:$port", "NTIVO_EVAL_OUT" to File(dir, "out").path),
            out = { output.appendLine(it) }
        )
        assertNull(runFile)
        assertTrue(output.contains("Cannot reach"), output.toString())
    }

    @Test
    fun `NTIVO_URL defaults to localhost 8080`() {
        assertEquals("http://localhost:8080", EvalConfig.from(emptyArray(), emptyMap()).baseUrl)
        assertEquals("http://h:1", EvalConfig.from(emptyArray(), mapOf("NTIVO_URL" to "http://h:1/")).baseUrl)
    }
}
