package io.ntivo.agent

import io.ntivo.AppContext
import io.ntivo.CodeGraph
import io.ntivo.EventBus
import io.ntivo.VectorStore
import io.ntivo.model.Piece
import io.ntivo.model.Source
import io.ntivo.model.SourceKind
import io.ntivo.model.SourceState
import io.ntivo.model.Stage
import io.ntivo.model.StageName
import io.ntivo.model.StageState
import io.ntivo.model.TraceHop
import io.ntivo.model.VerdictKind
import io.ntivo.sources.SourceStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnswerServiceTest {

    private val dir: File = Files.createTempDirectory("answers").toFile()
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

    private val android = Source("s1", "android-app", SourceKind.github, SourceState.ready, languages = listOf("Kotlin"))
    private val ios = Source(
        "s2", "ios-app", SourceKind.github, SourceState.indexing,
        stages = listOf(
            Stage(StageName.fetch, StageState.done),
            Stage(StageName.split, StageState.running, done = 6, total = 10),
            Stage(StageName.embed),
            Stage(StageName.connect)
        )
    )

    private val limitPiece = Piece(
        "11111111-1111-1111-1111-111111111111", "s1", "android-app", "Kotlin",
        "invite/InviteRepository.kt", "friendLimit", "InviteRepository.friendLimit", 40, 46,
        "fun friendLimit() = remoteConfig.getInt(\"friend_limit\", 5)"
    )
    private val unknownId = "99999999-9999-9999-9999-999999999999"

    /** Pretends to be the agent: "retrieves" [limitPiece], then returns [reply]. */
    private class FakeRunner(
        private val reply: String,
        private val piece: Piece? = null,
        private val repairReply: String? = null,
        private val delayMs: Long = 0
    ) : AgentRunner {
        var runs = 0
        var repairs = 0
        var lastSystemPrompt = ""
        override suspend fun run(systemPrompt: String, question: String, readySourceIds: List<String>, trace: TraceRecorder): String {
            runs++
            lastSystemPrompt = systemPrompt
            piece?.let { p -> trace.record("searchCodebase", question) { trace.retrieved(p, 0.82f); "found" to "1 piece" } }
            if (delayMs > 0) delay(delayMs)
            return reply
        }
        override suspend fun repair(rawOutput: String): String {
            repairs++
            return repairReply ?: rawOutput
        }
    }

    private fun service(runner: AgentRunner, budgetMs: Long = 60_000, lookup: suspend (String) -> Piece? = { null }) =
        AnswerService(ctx, lookup, runner, budgetMs)

    // --- parseModelOutput ---

    @Test
    fun `parses fenced JSON`() {
        val text = """
            Here you go:
            ```json
            {"verdict": {"kind": "mismatch", "text": "They differ"}, "prose": "Android allows 5.",
             "evidence": [{"label": "Android", "text": "limit 5", "pieceId": "p1"}], "couldNotSee": []}
            ```
        """.trimIndent()
        val out = assertNotNull(parseModelOutput(text))
        assertEquals(VerdictKind.mismatch, out.verdict?.kind)
        assertEquals("Android allows 5.", out.prose)
        assertEquals("p1", out.evidence.single().pieceId)
    }

    @Test
    fun `parses bare JSON with a null verdict and unknown keys`() {
        val out = assertNotNull(parseModelOutput("""{"verdict": null, "prose": "No.", "extra": 1}"""))
        assertNull(out.verdict)
        assertEquals("No.", out.prose)
        assertTrue(out.evidence.isEmpty())
    }

    @Test
    fun `tolerates an upper-case verdict kind`() {
        val out = assertNotNull(parseModelOutput("""{"verdict": {"kind": "CONFIRMED", "text": "Same"}, "prose": "Yes."}"""))
        assertEquals(VerdictKind.confirmed, out.verdict?.kind)
    }

    @Test
    fun `garbage does not parse`() {
        assertNull(parseModelOutput("I think the limit is 5."))
        assertNull(parseModelOutput("{not json at all}"))
        assertNull(parseModelOutput("""{"verdict": null}"""))
    }

    // --- validation ---

    @Test
    fun `a citation to a piece that was not retrieved is dropped and counted`() = runBlocking {
        val trace = TraceRecorder()
        trace.retrieved(limitPiece, 0.82f)
        val output = ModelOutput(
            prose = "Android allows 5 friends.",
            evidence = listOf(
                ModelEvidence("Android", "friendLimit returns 5", limitPiece.id),
                ModelEvidence("iOS", "limit is 10", unknownId)
            )
        )

        val answer = assembleAnswer("a1", "q", output, trace, emptyList(), 10) { null }

        assertEquals(1, answer.citations.size)
        assertEquals(limitPiece.id, answer.citations.single().pieceId)
        assertEquals("invite/InviteRepository.kt", answer.citations.single().path)
        assertEquals(40, answer.citations.single().startLine)
        assertEquals(1, answer.unverifiedCitations)
        // The row text stays; only its citation goes
        assertEquals(listOf("Android", "iOS"), answer.evidence.map { it.label })
        assertNull(answer.evidence[1].citation)
    }

    @Test
    fun `a citation to a graph-surfaced piece is looked up`() = runBlocking {
        val trace = TraceRecorder()
        trace.surfaced(limitPiece.id)
        val output = ModelOutput(prose = "p", evidence = listOf(ModelEvidence("Android", "t", limitPiece.id)))

        val answer = assembleAnswer("a1", "q", output, trace, emptyList(), 10) { id -> limitPiece.takeIf { it.id == id } }

        assertEquals(limitPiece.id, answer.citations.single().pieceId)
        assertEquals(0, answer.unverifiedCitations)
    }

    @Test
    fun `server-side unavailable sources are always in could not see`() = runBlocking {
        val output = ModelOutput(prose = "p", couldNotSee = listOf("ios-app", "Jira tickets"))
        val answer = assembleAnswer("a1", "q", output, TraceRecorder(), listOf("ios-app (indexing 40%)"), 10) { null }
        assertEquals(listOf("ios-app (indexing 40%)", "Jira tickets"), answer.couldNotSee)
    }

    // --- ask ---

    @Test
    fun `ask lists an indexing source under could not see even when the model forgets`() = runBlocking {
        ctx.sources.put(android)
        ctx.sources.put(ios)
        val reply = """{"verdict": null, "prose": "Android allows 5.", "evidence": [{"label": "Android", "text": "5", "pieceId": "${limitPiece.id}"}], "couldNotSee": []}"""
        val runner = FakeRunner(reply, limitPiece)

        val answer = service(runner).ask("What is the friend limit?", null)

        assertEquals(listOf("ios-app (indexing 40%)"), answer.couldNotSee)
        assertTrue("ios-app (indexing 40%)" in runner.lastSystemPrompt)
        assertEquals(limitPiece.id, answer.citations.single().pieceId)
        assertEquals(0, answer.unverifiedCitations)
    }

    @Test
    fun `ask drops a hallucinated citation`() = runBlocking {
        ctx.sources.put(android)
        val reply = """{"prose": "Both allow 5.", "evidence": [
            {"label": "Android", "text": "5", "pieceId": "${limitPiece.id}"},
            {"label": "iOS", "text": "5", "pieceId": "$unknownId"}]}"""

        val answer = service(FakeRunner(reply, limitPiece)).ask("q", null)

        assertEquals(1, answer.citations.size)
        assertEquals(1, answer.unverifiedCitations)
    }

    @Test
    fun `ask repairs unparseable output once, then falls back to raw text`() = runBlocking {
        ctx.sources.put(android)
        val repaired = FakeRunner("The limit is 5.", repairReply = """{"prose": "The limit is 5."}""")
        assertEquals("The limit is 5.", service(repaired).ask("q", null).prose)
        assertEquals(1, repaired.repairs)

        val hopeless = FakeRunner("The limit is 5.", repairReply = "Still not JSON")
        assertEquals("The limit is 5.", service(hopeless).ask("q", null).prose)
        assertEquals(1, hopeless.repairs)
    }

    @Test
    fun `ask with no sources answers without running the agent`() = runBlocking<Unit> {
        val runner = FakeRunner("{}")

        val answer = service(runner).ask("How does login work?", null)

        assertEquals(0, runner.runs)
        assertTrue(answer.prose.startsWith("No sources are connected"), answer.prose)
        assertTrue(answer.couldNotSee.isEmpty())
        assertNotNull(AnswerStore(ctx.tracesDir).trace(answer.id))
    }

    @Test
    fun `ask scoped to sources ignores the others`() = runBlocking {
        ctx.sources.put(android)
        ctx.sources.put(ios)
        val runner = FakeRunner("""{"prose": "ok"}""")

        val answer = service(runner).ask("q", listOf("s1"))

        assertTrue(answer.couldNotSee.isEmpty())
        assertTrue("ios-app" !in runner.lastSystemPrompt)
    }

    @Test
    fun `ask that runs out of time answers with what it found`() = runBlocking {
        ctx.sources.put(android)
        val runner = FakeRunner("""{"prose": "never"}""", limitPiece, delayMs = 5_000)

        val answer = service(runner, budgetMs = 200).ask("q", null)

        assertTrue(answer.prose.startsWith("I ran out of time before finishing."), answer.prose)
        assertEquals(limitPiece.id, answer.evidence.single().citation?.pieceId)
        val trace = assertNotNull(AnswerStore(ctx.tracesDir).trace(answer.id))
        assertTrue("time" in trace.limitsHit)
    }

    @Test
    fun `ask persists the answer and its trace`() = runBlocking {
        ctx.sources.put(android)
        val answer = service(FakeRunner("""{"prose": "ok"}""", limitPiece)).ask("q", null)

        val trace = assertNotNull(AnswerStore(ctx.tracesDir).trace(answer.id))
        assertEquals(answer.id, trace.answerId)
        assertEquals(limitPiece.id, trace.pieces.single().pieceId)
        assertEquals("searchCodebase", trace.toolCalls.single().tool)
        assertTrue(trace.explanation.contains("the closest piece"), trace.explanation)
    }

    // --- explanation ---

    @Test
    fun `explanation handles singular and plural`() {
        val one = TraceRecorder().apply {
            record("searchCodebase", "q") { "" to "" }
            retrieved(limitPiece, 0.8f)
            hop(TraceHop("a", "calls", "b", 1f))
        }
        assertEquals(
            "Your question was turned into a point in the same space as the code. " +
                "Ntivo took the closest piece, then followed 1 connection from it.",
            explanation(one.toTrace("a", "", emptyList(), "m"))
        )

        val many = TraceRecorder().apply {
            record("searchCodebase", "q") { "" to "" }
            retrieved(limitPiece, 0.8f)
            retrieved(limitPiece.copy(id = "p2"), 0.7f)
            retrieved(limitPiece.copy(id = "p3"), 0.7f)
            hop(TraceHop("a", "calls", "b", 1f))
            hop(TraceHop("c", "calls", "d", 1f))
        }
        assertEquals(
            "Your question was turned into a point in the same space as the code. " +
                "Ntivo took the 3 closest pieces, then followed 2 connections from them.",
            explanation(many.toTrace("a", "", emptyList(), "m"))
        )

        val none = TraceRecorder()
        assertEquals("Ntivo answered without looking at the code.", explanation(none.toTrace("a", "", emptyList(), "m")))
    }
}
