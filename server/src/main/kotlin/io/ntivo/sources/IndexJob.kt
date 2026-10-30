package io.ntivo.sources

import io.ntivo.AppContext
import io.ntivo.EventTypes
import io.ntivo.JobProgress
import io.ntivo.model.Piece
import io.ntivo.model.Source
import io.ntivo.model.SourceState
import io.ntivo.model.Stage
import io.ntivo.model.StageName
import io.ntivo.model.StageState
import io.ntivo.parse.Language
import io.ntivo.parse.ParsedFile
import io.ntivo.parse.parserFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** Stage 3: stores pieces for a source and returns how many were embedded. */
interface PieceIndexer {
    suspend fun index(
        sourceId: String,
        sourceName: String,
        pieces: List<Piece>,
        onProgress: (Int, Int) -> Unit
    ): Int
}

/** Stage 4: replaces the graph for a source. */
interface GraphIndexer {
    suspend fun rebuild(
        sourceId: String,
        sourceName: String,
        files: List<ParsedSourceFile>,
        onProgress: (Int, Int) -> Unit
    ): GraphStats
}

/** One parsed file, with its path relative to the source root. */
data class ParsedSourceFile(val path: String, val language: Language, val parsed: ParsedFile)

data class GraphStats(val classes: Int, val functions: Int, val callEdges: Int, val inheritsEdges: Int)

/** Error shown when a source has nothing Ntivo can split. */
const val NO_SUPPORTED_FILES = "No Kotlin or Swift files found"

/** Longest piece text kept. Longer functions are cut; their location still points at the whole body. */
const val MAX_PIECE_CHARS = 4000

/** Files larger than this are generated or vendored code, not worth a piece each. */
private const val MAX_FILE_BYTES = 1_000_000L

/** Minimum gap between two progress writes for the same stage, so big sources do not flood the bus. */
private const val PROGRESS_INTERVAL_MS = 150L

/**
 * Runs the four stages (fetch, split, embed, connect) for a source.
 *
 * Jobs run on [Dispatchers.IO] under a [SupervisorJob], one per source id. Every stage
 * change and (throttled) progress callback updates the [Source] in the store and emits
 * `source.updated` and `job.progress`. Any failure ends the source in state `failed`
 * with the error and the stage that failed; cancellation (on delete) just stops.
 */
class IndexJob(
    private val ctx: AppContext,
    private val pieceIndexer: PieceIndexer,
    private val graphIndexer: GraphIndexer
) {
    private val log = LoggerFactory.getLogger(IndexJob::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("index-job"))
    private val running = ConcurrentHashMap<String, Job>()

    /**
     * Stores [source] as indexing with fresh stages and starts its job. A job already
     * running for the same source id is cancelled first.
     */
    fun start(source: Source, fetcher: Fetcher): Job {
        running.remove(source.id)?.cancel()
        val fresh = source.copy(
            state = SourceState.indexing,
            error = null,
            stages = StageName.entries.map { Stage(it) }
        )
        ctx.sources.put(fresh)
        ctx.events.emit(EventTypes.SOURCE_UPDATED, fresh)

        val job = scope.launch(start = CoroutineStart.LAZY) { Run(fresh.id, fresh.name, fetcher).execute() }
        running[fresh.id] = job
        job.invokeOnCompletion { running.remove(fresh.id, job) }
        job.start()
        return job
    }

    /** Cancels the running job for [sourceId], if any, and waits until it has stopped. */
    suspend fun cancel(sourceId: String) {
        running[sourceId]?.cancelAndJoin()
    }

    fun isRunning(sourceId: String): Boolean = running[sourceId]?.isActive == true

    /** One execution of the four stages for one source. */
    private inner class Run(val sourceId: String, val sourceName: String, val fetcher: Fetcher) {
        private var current: StageName? = null
        private var stageStart = 0L
        private var lastEmit = 0L
        private val jobStart = System.currentTimeMillis()

        suspend fun execute() {
            try {
                // 1. Fetch
                begin(StageName.fetch)
                val dir = File(ctx.reposDir, sourceId)
                val fetched = fetcher.fetch(dir) { done, total, message ->
                    progress(StageName.fetch, done, total, message)
                }
                val fileCount = fetched.files.size
                complete(StageName.fetch, fileCount, fileCount, "Fetched $fileCount files")

                // 2. Split
                begin(StageName.split)
                val parsedFiles = split(fetched)
                val pieces = dedupe(parsedFiles.flatMap { toPieces(sourceId, sourceName, it) })
                if (pieces.isEmpty()) throw NoSupportedFilesException()
                complete(StageName.split, parsedFiles.size, parsedFiles.size, "Split into ${pieces.size} pieces")

                // 3. Embed
                begin(StageName.embed)
                val embedded = pieceIndexer.index(sourceId, sourceName, pieces) { done, total ->
                    progress(StageName.embed, done, total, "Capturing meaning")
                }
                complete(StageName.embed, pieces.size, pieces.size, "Capturing meaning")

                // 4. Connect
                begin(StageName.connect)
                val stats = graphIndexer.rebuild(sourceId, sourceName, parsedFiles) { done, total ->
                    progress(StageName.connect, done, total, "Mapping connections")
                }
                complete(StageName.connect, parsedFiles.size, parsedFiles.size, "Mapping connections")

                val languages = Language.entries
                    .filter { lang -> parsedFiles.any { it.language == lang } }
                    .map { it.displayName }
                val done = update {
                    it.copy(
                        state = SourceState.ready,
                        error = null,
                        files = fileCount,
                        pieces = pieces.size,
                        languages = languages,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                }
                done?.let { ctx.events.emit(EventTypes.SOURCE_UPDATED, it) }
                log.info(
                    "Indexed source {} ({}): {} files, {} pieces ({} embedded), {} call edges in {} ms. Stages: {}",
                    sourceId, sourceName, fileCount, pieces.size, embedded, stats.callEdges,
                    System.currentTimeMillis() - jobStart,
                    done?.stages?.joinToString { "${it.name}=${it.durationMs}ms" }
                )
            } catch (e: CancellationException) {
                log.info("Index job for source {} cancelled", sourceId)
                throw e
            } catch (e: Throwable) {
                fail(e)
            }
        }

        private suspend fun split(fetched: FetchResult): List<ParsedSourceFile> {
            val files = fetched.files
            val result = mutableListOf<ParsedSourceFile>()
            var pieceCount = 0
            progress(StageName.split, 0, files.size, "Split into 0 pieces", force = true)
            files.forEachIndexed { i, file ->
                coroutineContext.ensureActive()
                val language = Language.forFile(file.name)
                if (language != null && file.length() <= MAX_FILE_BYTES) {
                    val path = file.relativeTo(fetched.dir).invariantSeparatorsPath
                    try {
                        val parsed = parserFor(language).parse(file.readText())
                        result += ParsedSourceFile(path, language, parsed)
                        pieceCount += parsed.functions.size
                    } catch (e: Exception) {
                        // One unparsable file should not fail a whole repository
                        log.warn("Skipping {} in source {}: {}", path, sourceId, e.message)
                    }
                }
                progress(StageName.split, i + 1, files.size, "Split into $pieceCount pieces")
            }
            return result
        }

        private fun begin(stage: StageName) {
            current = stage
            stageStart = System.currentTimeMillis()
            lastEmit = 0L
            setStage(stage) { it.copy(state = StageState.running, done = 0, total = 0, durationMs = 0) }
                ?.let { ctx.events.emit(EventTypes.SOURCE_UPDATED, it) }
        }

        private fun progress(stage: StageName, done: Int, total: Int, message: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && done < total && now - lastEmit < PROGRESS_INTERVAL_MS) return
            lastEmit = now
            val updated = setStage(stage) { it.copy(done = done, total = total, durationMs = now - stageStart) }
                ?: return
            ctx.events.emit(EventTypes.SOURCE_UPDATED, updated)
            ctx.events.emit(EventTypes.JOB_PROGRESS, JobProgress(sourceId, stage, done, total, message))
        }

        private fun complete(stage: StageName, done: Int, total: Int, message: String) {
            val now = System.currentTimeMillis()
            val updated = setStage(stage) {
                it.copy(state = StageState.done, done = done, total = total, durationMs = now - stageStart)
            } ?: return
            ctx.events.emit(EventTypes.SOURCE_UPDATED, updated)
            ctx.events.emit(EventTypes.JOB_PROGRESS, JobProgress(sourceId, stage, done, total, message))
        }

        private fun fail(e: Throwable) {
            val message = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            if (e is NoSupportedFilesException) {
                log.info("Source {} ({}): {}", sourceId, sourceName, message)
            } else {
                log.warn("Index job for source {} failed in stage {}: {}", sourceId, current, message, e)
            }
            val now = System.currentTimeMillis()
            val updated = update { source ->
                source.copy(
                    state = SourceState.failed,
                    error = message,
                    stages = source.stages.map {
                        if (it.name == current) it.copy(state = StageState.failed, durationMs = now - stageStart) else it
                    }
                )
            } ?: return
            ctx.events.emit(EventTypes.SOURCE_UPDATED, updated)
        }

        private fun setStage(stage: StageName, f: (Stage) -> Stage): Source? = update { source ->
            source.copy(stages = source.stages.map { if (it.name == stage) f(it) else it })
        }

        /** Updates the stored source; null when it was removed meanwhile (deleted mid-job). */
        private fun update(f: (Source) -> Source): Source? =
            try {
                ctx.sources.update(sourceId, f)
            } catch (e: NoSuchElementException) {
                null
            }
    }

    private class NoSupportedFilesException : Exception(NO_SUPPORTED_FILES)
}

/** Keeps the first piece for each id: the same signature twice in one file (e.g. `#if` branches) is one piece. */
private fun dedupe(pieces: List<Piece>): List<Piece> = pieces.distinctBy { it.id }

/**
 * One piece per function in [file]. Text is the function body with obvious secrets
 * redacted, capped at [MAX_PIECE_CHARS].
 */
fun toPieces(sourceId: String, sourceName: String, file: ParsedSourceFile): List<Piece> =
    file.parsed.functions.map { fn ->
        Piece(
            id = pieceId(sourceId, file.path, fn),
            sourceId = sourceId,
            sourceName = sourceName,
            language = file.language.displayName,
            path = file.path,
            name = fn.name,
            qualifiedName = listOfNotNull(fn.container, fn.receiver, fn.name).joinToString("."),
            startLine = fn.startLine,
            endLine = fn.endLine,
            text = redactSecrets(fn.body).take(MAX_PIECE_CHARS)
        )
    }

/**
 * The index job the server runs, called once from `Application.module()`.
 * W1 wires the real piece indexer (QdrantPieceIndexer) here, so `Application.kt` never changes.
 */
fun createIndexJob(ctx: AppContext): IndexJob = IndexJob(
    ctx,
    pieceIndexer = object : PieceIndexer {
        override suspend fun index(
            sourceId: String,
            sourceName: String,
            pieces: List<Piece>,
            onProgress: (Int, Int) -> Unit
        ): Int = throw NotImplementedError("Filled in by W1 (Task 6)")
    },
    graphIndexer = ctx.codeGraph
)
