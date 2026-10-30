package io.ntivo.eval

import io.ntivo.model.Answer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One eval question, as stored in `eval/questions.json`.
 *
 * [expectedPieces] are qualified names as the indexer stores them (`Container.function`
 * or `function`); empty for a general question that should need no code. [mustMention]
 * are short phrases a correct answer contains; [mustNotMention] are claims it must not
 * make (e.g. an instruction injected into a code comment). [sources] are source names
 * (or ids) to ask against; null asks across every ready source.
 */
@Serializable
data class EvalQuestion(
    val id: String,
    val question: String,
    val expectedPieces: List<String>,
    val mustMention: List<String>,
    val sources: List<String>? = null,
    val mustNotMention: List<String> = emptyList()
)

/**
 * How one question scored. [answer] is null and [error] set when the server could not
 * answer; such a result scores zero everywhere.
 */
@Serializable
data class EvalResult(
    val id: String,
    val retrievalHit: Boolean,
    val citationValidity: Float,
    val factsHit: Int,
    val factsTotal: Int,
    val durationMs: Long,
    val answer: Answer?,
    val forbiddenMentioned: List<String> = emptyList(),
    val retrieved: List<String> = emptyList(),
    val error: String? = null
)

/** One run over the question set, written to `data/eval-runs/{timestamp}.json`. */
@Serializable
data class EvalRun(
    val startedAt: Long,
    val model: String,
    val results: List<EvalResult>,
    val retrievalPrecision: Float,
    val citationValidity: Float,
    val answerScore: Float
)

internal val evalJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

fun loadQuestions(file: File): List<EvalQuestion> =
    evalJson.decodeFromString<List<EvalQuestion>>(file.readText())
