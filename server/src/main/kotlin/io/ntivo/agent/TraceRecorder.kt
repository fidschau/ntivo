package io.ntivo.agent

import io.ntivo.model.Piece
import io.ntivo.model.ToolCall
import io.ntivo.model.Trace
import io.ntivo.model.TraceHop
import io.ntivo.model.TracePiece

/** Guardrail: tool calls allowed per question. */
const val MAX_TOOL_CALLS = 6

/** Guardrail: graph traversals (callers or callees) allowed per question. */
const val MAX_GRAPH_HOPS = 3

/** What a tool returns once the per-question budget is spent. */
const val TOOL_LIMIT_MESSAGE = "Tool limit reached. Answer with what you have."

/** What a graph tool returns once the hop budget is spent. */
const val HOP_LIMIT_MESSAGE = "Connection limit reached. Answer with what you have."

/**
 * Everything one question did: tool calls in order, pieces retrieved, connections
 * followed and limits hit. One instance per question, shared by every tool of that run.
 *
 * Also the source of truth for citation validation: [retrievedPieceIds] is the set of
 * piece ids the server actually handed to the model.
 *
 * [onToolCall] is told about each tool call before it runs (used for live activity).
 * Thread-safe: every mutation holds [lock].
 */
class TraceRecorder(
    val maxToolCalls: Int = MAX_TOOL_CALLS,
    val maxHops: Int = MAX_GRAPH_HOPS,
    private val onToolCall: (tool: String, args: String) -> Unit = { _, _ -> }
) {
    @PublishedApi
    internal val lock = Any()

    private val toolCalls = mutableListOf<ToolCall>()
    private val pieces = LinkedHashMap<String, TracePiece>()
    private val pieceTexts = HashMap<String, Piece>()
    private val surfacedIds = LinkedHashSet<String>()
    private val hops = mutableListOf<TraceHop>()
    private val limits = LinkedHashSet<String>()
    private var hopsUsed = 0

    /** Ids of pieces returned by search plus functions shown by graph queries. */
    val retrievedPieceIds: Set<String>
        get() = synchronized(lock) { pieces.keys + surfacedIds }

    val toolCallCount: Int get() = synchronized(lock) { toolCalls.size }

    /** False once [maxToolCalls] calls were made; the refusal is recorded as limit `toolCalls`. */
    fun allow(): Boolean = synchronized(lock) {
        val ok = toolCalls.size < maxToolCalls
        if (!ok) limits += "toolCalls"
        ok
    }

    /** False once [maxHops] graph traversals were made; the refusal is recorded as limit `hops`. */
    fun allowHop(): Boolean = synchronized(lock) {
        val ok = hopsUsed < maxHops
        if (ok) hopsUsed++ else limits += "hops"
        ok
    }

    /**
     * Runs [block], which returns the tool result and a one-line summary, and appends a
     * [ToolCall] with its duration. A throwing block is recorded with the error as summary.
     */
    inline fun <T> record(tool: String, args: String, block: () -> Pair<T, String>): T {
        notifyToolCall(tool, args)
        val start = System.currentTimeMillis()
        val (result, summary) = try {
            block()
        } catch (e: Throwable) {
            addToolCall(ToolCall(tool, args, "failed: ${e.message}", System.currentTimeMillis() - start))
            throw e
        }
        addToolCall(ToolCall(tool, args, summary, System.currentTimeMillis() - start))
        return result
    }

    /** [record] behind the [allow] check: returns [TOOL_LIMIT_MESSAGE] when the budget is spent. */
    inline fun guarded(tool: String, args: String, block: () -> Pair<String, String>): String {
        if (!allow()) return TOOL_LIMIT_MESSAGE
        return record(tool, args, block)
    }

    @PublishedApi
    internal fun notifyToolCall(tool: String, args: String) {
        runCatching { onToolCall(tool, args) }
    }

    @PublishedApi
    internal fun addToolCall(call: ToolCall) {
        synchronized(lock) { toolCalls += call }
    }

    /** A piece returned by search. Kept once, with its best score. */
    fun retrieved(piece: TracePiece) = synchronized(lock) {
        val existing = pieces[piece.pieceId]
        if (existing == null || piece.score > existing.score) pieces[piece.pieceId] = piece
    }

    /** A piece returned by search, keeping its text so citations need no second lookup. */
    fun retrieved(piece: Piece, score: Float) {
        retrieved(TracePiece(piece.id, piece.name, piece.sourceName, piece.language, score))
        synchronized(lock) { pieceTexts[piece.id] = piece }
    }

    /** A piece id shown to the model by a graph query. Citable, but not a search result. */
    fun surfaced(pieceId: String) = synchronized(lock) { surfacedIds += pieceId }

    fun hop(hop: TraceHop) = synchronized(lock) { hops += hop }

    fun limit(name: String) = synchronized(lock) { limits += name }

    /** The full piece for [pieceId] if search returned it. */
    fun piece(pieceId: String): Piece? = synchronized(lock) { pieceTexts[pieceId] }

    /** Search results in retrieval order, best score kept. */
    fun retrievedPieces(): List<TracePiece> = synchronized(lock) { pieces.values.toList() }

    fun hops(): List<TraceHop> = synchronized(lock) { hops.toList() }

    fun toTrace(answerId: String, explanation: String, unavailable: List<String>, model: String): Trace =
        synchronized(lock) {
            Trace(
                answerId = answerId,
                explanation = explanation,
                pieces = pieces.values.toList(),
                hops = hops.toList(),
                unavailable = unavailable,
                toolCalls = toolCalls.toList(),
                limitsHit = limits.toList(),
                model = model
            )
        }
}
