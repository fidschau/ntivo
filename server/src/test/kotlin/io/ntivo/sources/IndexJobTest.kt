package io.ntivo.sources

import io.ntivo.AppContext
import io.ntivo.CodeGraph
import io.ntivo.Event
import io.ntivo.EventBus
import io.ntivo.EventTypes
import io.ntivo.FunctionChunk
import io.ntivo.VectorStore
import io.ntivo.model.Piece
import io.ntivo.model.Source
import io.ntivo.model.SourceKind
import io.ntivo.model.SourceState
import io.ntivo.model.StageName
import io.ntivo.model.StageState
import io.ntivo.parse.Language
import io.ntivo.parse.ParsedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IndexJobTest {

    private val dataDir = Files.createTempDirectory("indexjob").toFile()
    private val ctx = AppContext(
        sources = SourceStore(File(dataDir, "sources.json")),
        events = EventBus(),
        vectorStore = VectorStore(),
        codeGraph = CodeGraph(),
        embedder = null,
        executor = null,
        dataDir = dataDir
    )

    @AfterTest
    fun tearDown() {
        ctx.close()
        dataDir.deleteRecursively()
    }

    private fun source(id: String = "s1") = Source(id, "android-app", SourceKind.upload, SourceState.indexing)

    /** Writes [files] (relative path to content) into the job's working directory. */
    private class FakeFetcher(private val files: Map<String, String>) : Fetcher {
        override suspend fun fetch(into: File, onProgress: (Int, Int, String) -> Unit): FetchResult {
            into.mkdirs()
            files.forEach { (path, text) -> File(into, path).apply { parentFile.mkdirs(); writeText(text) } }
            onProgress(files.size, files.size, "done")
            return FetchResult(into, supportedFiles(into), "android-app")
        }
    }

    /** Records each call together with the stage states the job had stored at that moment. */
    private inner class RecordingPieceIndexer(private val fail: Boolean = false) : PieceIndexer {
        var pieces: List<Piece> = emptyList()
        var stagesAtCall: List<StageState> = emptyList()
        override suspend fun index(sourceId: String, sourceName: String, pieces: List<Piece>, onProgress: (Int, Int) -> Unit): Int {
            stagesAtCall = ctx.sources.get(sourceId)!!.stages.map { it.state }
            if (fail) throw IllegalStateException("Embedding quota exceeded")
            this.pieces = pieces
            onProgress(pieces.size, pieces.size)
            return pieces.size
        }
    }

    private inner class RecordingGraphIndexer : GraphIndexer {
        var files: List<ParsedSourceFile> = emptyList()
        var stagesAtCall: List<StageState> = emptyList()
        override suspend fun rebuild(sourceId: String, sourceName: String, files: List<ParsedSourceFile>, onProgress: (Int, Int) -> Unit): GraphStats {
            stagesAtCall = ctx.sources.get(sourceId)!!.stages.map { it.state }
            this.files = files
            onProgress(files.size, files.size)
            return GraphStats(0, 0, 0, 0)
        }
    }

    private val twoFiles = mapOf(
        "app/Repo.kt" to "class Repo {\n  fun load(id: String) = id\n  fun save() {}\n}\n",
        "ios/InviteService.swift" to "final class InviteService {\n  func friendLimit() -> Int { return 10 }\n}\n",
        "README.md" to "# not code"
    )

    /** Runs a job to completion while collecting every event it emits. */
    private fun runJob(job: IndexJob, source: Source, fetcher: Fetcher): List<Event> = runBlocking {
        val events = mutableListOf<Event>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { ctx.events.flow.collect { events += it } }
        withTimeout(10_000) { job.start(source, fetcher).join() }
        // Let the collector drain what is buffered
        kotlinx.coroutines.delay(100)
        collector.cancel()
        events
    }

    @Test
    fun `a successful job runs the four stages in order and ends ready`() {
        val pieceIndexer = RecordingPieceIndexer()
        val graphIndexer = RecordingGraphIndexer()
        runJob(IndexJob(ctx, pieceIndexer, graphIndexer), source(), FakeFetcher(twoFiles))

        val s = ctx.sources.get("s1")!!
        assertEquals(SourceState.ready, s.state)
        assertNull(s.error)
        assertEquals(StageName.entries, s.stages.map { it.name })
        assertTrue(s.stages.all { it.state == StageState.done }, "stages were ${s.stages}")
        assertEquals(3, s.pieces)
        assertEquals(2, s.files)
        assertEquals(listOf("Kotlin", "Swift"), s.languages)
        assertTrue(s.lastSyncedAt != null)

        assertEquals(listOf(StageState.done, StageState.done, StageState.running, StageState.todo), pieceIndexer.stagesAtCall)
        assertEquals(listOf(StageState.done, StageState.done, StageState.done, StageState.running), graphIndexer.stagesAtCall)
        assertEquals(setOf("Repo.load", "Repo.save", "InviteService.friendLimit"), pieceIndexer.pieces.map { it.qualifiedName }.toSet())
        assertEquals(listOf("app/Repo.kt", "ios/InviteService.swift"), graphIndexer.files.map { it.path })
        assertEquals(listOf(Language.KOTLIN, Language.SWIFT), graphIndexer.files.map { it.language })
    }

    @Test
    fun `a source with no supported files fails with a clear error instead of hanging`() {
        val pieceIndexer = RecordingPieceIndexer()
        runJob(IndexJob(ctx, pieceIndexer, RecordingGraphIndexer()), source(), FakeFetcher(mapOf("docs/README.md" to "# docs")))

        val s = ctx.sources.get("s1")!!
        assertEquals(SourceState.failed, s.state)
        assertEquals("No Kotlin or Swift files found", s.error)
        assertEquals(StageState.done, s.stages[0].state)
        assertEquals(StageState.failed, s.stages[1].state)
        assertEquals(emptyList(), pieceIndexer.stagesAtCall)
    }

    @Test
    fun `an indexer that throws fails the embed stage`() {
        runJob(IndexJob(ctx, RecordingPieceIndexer(fail = true), RecordingGraphIndexer()), source(), FakeFetcher(twoFiles))

        val s = ctx.sources.get("s1")!!
        assertEquals(SourceState.failed, s.state)
        assertEquals("Embedding quota exceeded", s.error)
        assertEquals(StageState.failed, s.stages[2].state)
        assertEquals(StageState.todo, s.stages[3].state)
    }

    @Test
    fun `progress and source events are emitted for every stage`() {
        val events = runJob(IndexJob(ctx, RecordingPieceIndexer(), RecordingGraphIndexer()), source(), FakeFetcher(twoFiles))

        val progressStages = events.filter { it.type == EventTypes.JOB_PROGRESS }
            .map { it.data.jsonObjectField("stage") }.toSet()
        assertEquals(StageName.entries.map { it.name }.toSet(), progressStages)
        val messages = events.filter { it.type == EventTypes.JOB_PROGRESS }.map { it.data.jsonObjectField("message") }
        assertTrue("Fetched 2 files" in messages, "messages were $messages")
        assertTrue("Split into 3 pieces" in messages, "messages were $messages")
        assertTrue("Capturing meaning" in messages)
        assertTrue("Mapping connections" in messages)
        val lastUpdate = events.last { it.type == EventTypes.SOURCE_UPDATED }
        assertEquals("ready", lastUpdate.data.jsonObjectField("state"))
    }

    @Test
    fun `cancel stops a running job`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val hanging = object : Fetcher {
            override suspend fun fetch(into: File, onProgress: (Int, Int, String) -> Unit): FetchResult {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val indexJob = IndexJob(ctx, RecordingPieceIndexer(), RecordingGraphIndexer())
        val job = indexJob.start(source(), hanging)
        withTimeout(5_000) { started.await() }
        assertTrue(indexJob.isRunning("s1"))
        withTimeout(5_000) { indexJob.cancel("s1") }
        assertTrue(job.isCancelled)
        assertFalse(indexJob.isRunning("s1"))
    }

    @Test
    fun `pieces carry the source, a qualified name and capped text`() {
        val long = "x".repeat(5000)
        val file = ParsedSourceFile(
            "app/Repo.kt", Language.KOTLIN,
            ParsedFile(
                listOf(
                    FunctionChunk("load", null, "(id: String)", 2, 4, "fun load(id: String) = 1", container = "Repo"),
                    FunctionChunk("shout", "String", "()", 6, 6, long)
                ),
                emptyList()
            )
        )
        val pieces = toPieces("s1", "android-app", file)
        assertEquals(listOf("Repo.load", "String.shout"), pieces.map { it.qualifiedName })
        assertEquals(pieceId("s1", "app/Repo.kt", file.parsed.functions[0]), pieces[0].id)
        assertEquals("Kotlin", pieces[0].language)
        assertEquals("android-app", pieces[0].sourceName)
        assertEquals(4000, pieces[1].text.length)
    }

    @Test
    fun `secrets in code are redacted before indexing`() {
        val body = "val key = \"AIzaSyA1234567890abcdefghijklmnopqrstuv\"\nval gh = \"ghp_abcdefghijklmnopqrstuvwxyz0123456789\""
        val file = ParsedSourceFile(
            "Keys.kt", Language.KOTLIN,
            ParsedFile(listOf(FunctionChunk("keys", null, "()", 1, 2, body)), emptyList())
        )
        val text = toPieces("s1", "app", file).single().text
        assertFalse("AIzaSy" in text, text)
        assertFalse("ghp_abc" in text, text)
        assertTrue("[REDACTED]" in text)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectField(key: String) =
        (this as kotlinx.serialization.json.JsonObject)[key]!!.jsonPrimitive.content
}
