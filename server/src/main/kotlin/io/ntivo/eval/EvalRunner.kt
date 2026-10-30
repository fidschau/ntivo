package io.ntivo.eval

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ntivo.AppContext
import io.ntivo.agent.AskRequest
import io.ntivo.model.Answer
import io.ntivo.model.Piece
import io.ntivo.model.Source
import io.ntivo.model.Trace
import io.ntivo.shared.ErrorResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Where the runner reads questions, which server it asks and where it writes the run.
 *
 * - [questionsFile]: first argument, default `eval/questions.json`.
 * - [baseUrl]: `NTIVO_URL`, default `http://localhost:8080`.
 * - [outDir]: `NTIVO_EVAL_OUT`, default `data/eval-runs` under the data directory.
 * - [delayMs]: `NTIVO_EVAL_DELAY_MS` between questions, default 0. Raise it to stay under a
 *   free-tier model quota.
 */
data class EvalConfig(val questionsFile: File, val baseUrl: String, val outDir: File, val delayMs: Long) {
    companion object {
        fun from(args: Array<String>, env: Map<String, String>): EvalConfig {
            fun env(name: String) = env[name]?.takeIf { it.isNotBlank() }
            return EvalConfig(
                questionsFile = File(args.firstOrNull()?.takeIf { it.isNotBlank() } ?: "eval/questions.json"),
                baseUrl = (env("NTIVO_URL") ?: "http://localhost:8080").trimEnd('/'),
                outDir = env("NTIVO_EVAL_OUT")?.let(::File) ?: File(AppContext.defaultDataDir(), "eval-runs"),
                delayMs = env("NTIVO_EVAL_DELAY_MS")?.toLongOrNull() ?: 0L
            )
        }
    }
}

/**
 * Asks every question of the eval set to a running Ntivo server and scores the answers.
 * `./gradlew :server:runEval [-Pquestions=path]`, with the server and sources already up.
 */
fun main(args: Array<String>) {
    val file = evalMain(args, System.getenv(), ::println)
    if (file == null) kotlin.system.exitProcess(1)
}

/** Runs the eval and returns the written run file, or null when the server could not be reached. */
fun evalMain(args: Array<String>, env: Map<String, String>, out: (String) -> Unit): File? {
    val config = EvalConfig.from(args, env)
    val questions = loadQuestions(config.questionsFile)
    out("Asking ${questions.size} questions to ${config.baseUrl}")

    val run = EvalRunner(config.baseUrl, config.delayMs, out).use { runner ->
        runBlocking { runner.run(questions) }
    } ?: return null

    config.outDir.mkdirs()
    val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
        .format(Instant.ofEpochMilli(run.startedAt))
    val file = File(config.outDir, "$stamp.json")
    file.writeText(evalJson.encodeToString(EvalRun.serializer(), run))

    out("")
    out(formatRun(run))
    out("")
    out("Written to ${file.path}")
    return file
}

/** Talks to a running Ntivo server over its public API. */
class EvalRunner(
    private val baseUrl: String,
    private val delayMs: Long = 0,
    private val out: (String) -> Unit = {}
) : AutoCloseable {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(evalJson) }
        // One answer may take the server's full 60 s budget plus a repair round
        install(HttpTimeout) { requestTimeoutMillis = 180_000 }
        expectSuccess = false
    }

    private val pieceCache = HashMap<String, Piece?>()

    /** Scores every question in order. Null when the server is unreachable. */
    suspend fun run(questions: List<EvalQuestion>): EvalRun? {
        val startedAt = System.currentTimeMillis()
        val sources = try {
            val response = client.get("$baseUrl/api/sources")
            if (!response.status.isSuccess()) {
                out("Cannot reach Ntivo at $baseUrl: GET /api/sources returned ${describe(response)}")
                return null
            }
            response.body<List<Source>>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out("Cannot reach Ntivo at $baseUrl (${e.message ?: e::class.simpleName}). Start it with ./gradlew :server:run")
            return null
        }

        var model: String? = null
        val results = questions.mapIndexed { i, question ->
            if (i > 0 && delayMs > 0) delay(delayMs)
            out("[${i + 1}/${questions.size}] ${question.id}")
            val (result, traceModel) = ask(question, sources)
            if (model == null) model = traceModel?.takeIf { it.isNotBlank() }
            result
        }
        return aggregate(startedAt, model ?: "unknown", results)
    }

    private suspend fun ask(question: EvalQuestion, sources: List<Source>): Pair<EvalResult, String?> {
        val start = System.currentTimeMillis()
        fun failed(error: String) = failedResult(question, error, System.currentTimeMillis() - start) to null

        val sourceIds = question.sources?.let { names ->
            val ids = names.map { name -> sources.firstOrNull { it.name == name || it.id == name }?.id }
            val unknown = names.filterIndexed { i, _ -> ids[i] == null }
            if (unknown.isNotEmpty()) return failed("Unknown source: ${unknown.joinToString()}")
            ids.filterNotNull()
        }

        val answer = try {
            val response = client.post("$baseUrl/api/ask") {
                contentType(ContentType.Application.Json)
                setBody(AskRequest(question.question, sourceIds))
            }
            if (!response.status.isSuccess()) return failed(describe(response))
            response.body<Answer>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return failed(e.message ?: e::class.simpleName ?: "error")
        }

        val trace = getOrNull<Trace>("/api/answers/${answer.id.encodeURLPathPart()}/trace")
        val retrieved = trace?.pieces.orEmpty().map { p ->
            RetrievedPiece(p.name, piece(p.pieceId)?.qualifiedName?.takeIf { it.isNotEmpty() })
        }
        return scoreQuestion(question, answer, retrieved) to trace?.model
    }

    /** The trace only names pieces; their qualified names come from the piece endpoint. */
    private suspend fun piece(id: String): Piece? =
        pieceCache.getOrPut(id) { getOrNull<Piece>("/api/pieces/${id.encodeURLPathPart()}") }

    private suspend inline fun <reified T> getOrNull(path: String): T? = try {
        val response = client.get("$baseUrl$path")
        if (response.status.isSuccess()) response.body<T>() else null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private suspend fun describe(response: HttpResponse): String {
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        val message = runCatching { evalJson.decodeFromString(ErrorResponse.serializer(), body).error }
            .getOrElse { body.take(200) }
        return "HTTP ${response.status.value}" + if (message.isBlank()) "" else ": $message"
    }

    override fun close() = client.close()
}

/** The per-question table and the three run numbers, for the terminal. */
fun formatRun(run: EvalRun): String = buildString {
    val idWidth = maxOf(8, run.results.maxOfOrNull { it.id.length } ?: 0)
    appendLine("${"Question".padEnd(idWidth)}  Found  Citations  Facts  Time     Notes")
    for (r in run.results) {
        val notes = listOfNotNull(
            r.error,
            r.forbiddenMentioned.takeIf { it.isNotEmpty() }?.let { "said ${it.joinToString()}" },
            r.answer?.unverifiedCitations?.takeIf { it > 0 }?.let { "$it unverified" }
        ).joinToString("; ")
        appendLine(
            "${r.id.padEnd(idWidth)}  ${(if (r.retrievalHit) "yes" else "no").padEnd(5)}  " +
                "${percent(r.citationValidity).padEnd(9)}  ${"${r.factsHit}/${r.factsTotal}".padEnd(5)}  " +
                "${"%.1fs".format(Locale.ROOT, r.durationMs / 1000.0).padEnd(7)}  $notes"
        )
    }
    appendLine()
    appendLine("Model:               ${run.model}")
    appendLine("Retrieval precision: ${percent(run.retrievalPrecision)}")
    appendLine("Citation validity:   ${percent(run.citationValidity)}")
    append("Answer score:        ${percent(run.answerScore)}")
}

private fun percent(value: Float) = "%.0f%%".format(Locale.ROOT, value * 100)
