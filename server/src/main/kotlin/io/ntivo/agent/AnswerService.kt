package io.ntivo.agent

import ai.koog.agents.core.agent.exception.AIAgentMaxNumberOfIterationsReachedException
import io.ntivo.AnswerActivity
import io.ntivo.AppContext
import io.ntivo.EventTypes
import io.ntivo.model.Answer
import io.ntivo.model.Citation
import io.ntivo.model.EvidenceRow
import io.ntivo.model.Piece
import io.ntivo.model.Source
import io.ntivo.model.SourceState
import io.ntivo.model.Trace
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Guardrail: wall-clock budget for one question. */
const val ANSWER_BUDGET_MS = 60_000L

/** Answers one question. [AnswerService] is the real one; routes depend on this for testing. */
fun interface QuestionAnswerer {
    suspend fun ask(question: String, sourceIds: List<String>?): Answer
}

/** Runs the model. [KoogAgentRunner] is the real one; tests substitute a fake. */
interface AgentRunner {
    /** Runs the agent with tools scoped to [readySourceIds], recording into [trace]; returns its final message. */
    suspend fun run(systemPrompt: String, question: String, readySourceIds: List<String>, trace: TraceRecorder): String

    /** Asks the model once more to turn [rawOutput] into the JSON object. */
    suspend fun repair(rawOutput: String): String
}

/** The Koog agent with search and graph tools, built per question from [ctx]. */
class KoogAgentRunner(private val ctx: AppContext) : AgentRunner {

    override suspend fun run(
        systemPrompt: String,
        question: String,
        readySourceIds: List<String>,
        trace: TraceRecorder
    ): String {
        val executor = checkNotNull(ctx.executor) { "No model key configured" }
        val embedder = checkNotNull(ctx.embedder) { "No model key configured" }
        val agent = createCodebaseAgent(
            executor = executor,
            systemPrompt = systemPrompt,
            searchTools = CodeSearchTools(embedder, ctx.vectorStore, ctx.collection, readySourceIds, trace),
            graphTools = GraphTools(ctx.codeGraph, readySourceIds, trace)
        )
        return agent.run(question)
    }

    override suspend fun repair(rawOutput: String): String {
        val executor = checkNotNull(ctx.executor) { "No model key configured" }
        return createRepairAgent(executor).run("Return only the JSON object.\n\n$rawOutput")
    }
}

/**
 * Answers a question about the ready sources with a structured, citation-validated answer.
 *
 * 1. Splits the sources in scope into ready and unavailable.
 * 2. Runs the agent within [budgetMs], streaming `answer.activity` events from its tool calls.
 * 3. Parses the JSON answer, asking once for a repair, else keeping the raw text as prose.
 * 4. Drops citations to pieces the server never handed out ([assembleAnswer]).
 * 5. Always lists unavailable sources under "could not see".
 * 6. Persists the answer and its trace under `data/traces/`.
 */
class AnswerService(
    private val ctx: AppContext,
    private val pieceLookup: suspend (String) -> Piece? = { ctx.vectorStore.getPiece(ctx.collection, it) },
    private val runner: AgentRunner = KoogAgentRunner(ctx),
    private val budgetMs: Long = ANSWER_BUDGET_MS
) : QuestionAnswerer {

    private val store = AnswerStore(ctx.tracesDir)

    override suspend fun ask(question: String, sourceIds: List<String>?): Answer {
        val start = System.currentTimeMillis()
        val answerId = UUID.randomUUID().toString()
        fun activity(message: String) =
            ctx.events.emit(EventTypes.ANSWER_ACTIVITY, AnswerActivity(answerId, message))

        val inScope = ctx.sources.all().filter { sourceIds == null || it.id in sourceIds }
        val ready = inScope.filter { it.state == SourceState.ready }
        val unavailable = inScope.filter { it.state != SourceState.ready }.map(::unavailableLabel)

        val trace = TraceRecorder { tool, _ -> activity(activityFor(tool, ready)) }

        val output = if (ready.isEmpty()) {
            nothingReadyOutput(inScope)
        } else {
            activity("Reading your question")
            answerWithAgent(question, inScope, ready, trace, start).also { activity("Writing answer") }
        }

        val assembled = assembleAnswer(answerId, question, output, trace, unavailable, 0, pieceLookup)
        val answer = assembled.copy(durationMs = System.currentTimeMillis() - start)
        val raw = trace.toTrace(answerId, "", unavailable, ANSWER_MODEL.id)
        store.save(AnswerRecord(answer, raw.copy(explanation = explanation(raw))))
        return answer
    }

    private suspend fun answerWithAgent(
        question: String,
        inScope: List<Source>,
        ready: List<Source>,
        trace: TraceRecorder,
        start: Long
    ): ModelOutput {
        val systemPrompt = buildSystemPrompt(inScope)
        val raw = try {
            withTimeoutOrNull(budgetMs) { runner.run(systemPrompt, question, ready.map { it.id }, trace) }
        } catch (e: AIAgentMaxNumberOfIterationsReachedException) {
            trace.limit("iterations")
            return partialOutput(trace, "I hit my step limit before finishing. Here is what I found so far.")
        }
        if (raw == null) {
            trace.limit("time")
            return partialOutput(trace, "I ran out of time before finishing. Here is what I found so far.")
        }

        parseModelOutput(raw)?.let { return it }
        val remaining = budgetMs - (System.currentTimeMillis() - start)
        val repaired = if (remaining > 0) {
            runCatching { withTimeoutOrNull(remaining) { runner.repair(raw) } }.getOrNull()
        } else null
        return repaired?.let(::parseModelOutput) ?: ModelOutput(prose = raw.trim())
    }
}

/**
 * Builds the [Answer] from the model's output, keeping only citations to pieces in
 * [TraceRecorder.retrievedPieceIds]. A dropped citation keeps its row text and counts
 * towards [Answer.unverifiedCitations]. [unavailable] (server state) always leads
 * [Answer.couldNotSee].
 */
suspend fun assembleAnswer(
    answerId: String,
    question: String,
    output: ModelOutput,
    trace: TraceRecorder,
    unavailable: List<String>,
    durationMs: Long,
    pieceLookup: suspend (String) -> Piece?
): Answer {
    val retrieved = trace.retrievedPieceIds
    val citations = LinkedHashMap<String, Citation>()
    var unverified = 0

    val rows = output.evidence.map { row ->
        val id = row.pieceId ?: return@map EvidenceRow(row.label, row.text)
        val piece = if (id in retrieved) trace.piece(id) ?: runCatching { pieceLookup(id) }.getOrNull() else null
        if (piece == null) {
            unverified++
            EvidenceRow(row.label, row.text)
        } else {
            val citation = piece.toCitation()
            citations.putIfAbsent(citation.pieceId, citation)
            EvidenceRow(row.label, row.text, citation)
        }
    }

    return Answer(
        id = answerId,
        question = question,
        verdict = output.verdict,
        prose = output.prose,
        evidence = rows,
        citations = citations.values.toList(),
        couldNotSee = mergeCouldNotSee(unavailable, output.couldNotSee),
        unverifiedCitations = unverified,
        durationMs = durationMs
    )
}

/**
 * Server entries first, then the model's entries that do not repeat them
 * (the model often names "ios-app" where the server says "ios-app (indexing 40%)").
 */
fun mergeCouldNotSee(server: List<String>, model: List<String>): List<String> {
    val names = server.map { it.substringBefore(" (").lowercase() }
    val merged = server.toMutableList()
    for (entry in model) {
        val lower = entry.trim().lowercase()
        if (lower.isEmpty()) continue
        if (merged.any { it.lowercase() == lower } || names.any { it.isNotEmpty() && lower.contains(it) }) continue
        merged += entry.trim()
    }
    return merged
}

/** The plain-words explanation at the top of the trace panel. */
fun explanation(trace: Trace): String {
    if (trace.toolCalls.isEmpty()) return "Ntivo answered without looking at the code."
    val n = trace.pieces.size
    val m = trace.hops.size
    val connections = "$m connection${if (m == 1) "" else "s"}"
    if (trace.toolCalls.none { it.tool == "searchCodebase" }) {
        return "Ntivo followed $connections in the code."
    }
    val base = "Your question was turned into a point in the same space as the code."
    if (n == 0) {
        return "$base No piece of code was close enough to use." + if (m > 0) " Ntivo followed $connections." else ""
    }
    val pieces = if (n == 1) "the closest piece" else "the $n closest pieces"
    val follow = if (m > 0) ", then followed $connections from ${if (n == 1) "it" else "them"}." else "."
    return "$base Ntivo took $pieces$follow"
}

private fun Piece.toCitation() = Citation(id, sourceName, path, startLine, endLine)

/** Live activity line for a tool call. */
private fun activityFor(tool: String, ready: List<Source>): String = when (tool) {
    "searchCodebase" -> if (ready.size <= 2) "Searching ${ready.joinToString(" and ") { it.name }}"
    else "Searching ${ready.size} sources"
    else -> "Following connections"
}

/** When the agent stops early: whatever search returned, cited, under [prose]. */
private fun partialOutput(trace: TraceRecorder, prose: String) = ModelOutput(
    prose = prose,
    evidence = trace.retrievedPieces().map { ModelEvidence(it.sourceName, it.name, it.pieceId) }
)

private fun nothingReadyOutput(inScope: List<Source>) = ModelOutput(
    prose = if (inScope.isEmpty()) {
        "No sources are connected yet, so there is no code to answer from. Connect a repository and ask again."
    } else {
        "None of the sources are ready yet, so I could not look at any code. Ask again when indexing finishes."
    }
)
