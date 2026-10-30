package io.ntivo.sources

import io.ntivo.AppContext
import io.ntivo.model.Piece
import io.ntivo.model.Source
import io.ntivo.parse.Language
import io.ntivo.parse.ParsedFile
import kotlinx.coroutines.Job

// Compile-only skeleton from W0. W1 (Task 5) implements IndexJob.start.

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

/** Runs the four stages (fetch, split, embed, connect) for a source. */
class IndexJob(
    private val ctx: AppContext,
    private val pieceIndexer: PieceIndexer,
    private val graphIndexer: GraphIndexer
) {
    fun start(source: Source, fetcher: Fetcher): Job = throw NotImplementedError("Filled in by W1 (Task 5)")
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
    graphIndexer = object : GraphIndexer {
        override suspend fun rebuild(
            sourceId: String,
            sourceName: String,
            files: List<ParsedSourceFile>,
            onProgress: (Int, Int) -> Unit
        ): GraphStats = throw NotImplementedError("CodeGraph implements this in Task 7")
    }
)
