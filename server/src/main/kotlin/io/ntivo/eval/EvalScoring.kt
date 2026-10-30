package io.ntivo.eval

import io.ntivo.model.Answer

// Deterministic scoring. An LLM-graded answer score is wave 2.

/** A piece from an answer's trace. [qualifiedName] is null when the piece could not be looked up. */
data class RetrievedPiece(val name: String, val qualifiedName: String?)

/**
 * True when any of [expected] (qualified names) was retrieved. A piece whose qualified name
 * is unknown matches on its simple name. A question expecting no pieces hits only when
 * nothing was retrieved: a general question should not need the code.
 */
fun retrievalHit(expected: List<String>, retrieved: List<RetrievedPiece>): Boolean {
    if (expected.isEmpty()) return retrieved.isEmpty()
    return retrieved.any { piece ->
        if (piece.qualifiedName != null) piece.qualifiedName in expected
        else expected.any { it.substringAfterLast('.') == piece.name }
    }
}

/** Verified citations over all citations the model made; 1 when it made none. */
fun citationValidity(answer: Answer): Float {
    val verified = answer.citations.size
    val total = verified + answer.unverifiedCitations
    return if (total == 0) 1f else verified.toFloat() / total
}

/** Everything the answer says: verdict, prose and evidence rows. */
private fun answerText(answer: Answer): String = buildString {
    answer.verdict?.let { appendLine(it.text) }
    appendLine(answer.prose)
    answer.evidence.forEach { appendLine("${it.label} ${it.text}") }
}

/**
 * True when [text] states [phrase] as a token of its own, ignoring case: no letter or digit
 * may touch it on either side, so "6" is not found in "60" and "4" not in "14-18" or "2024".
 * With [allowWordSuffix], a phrase that ends in a letter may run on into a longer word, so
 * "symbolic link" is found in "symbolic links". A phrase ending in a digit never may.
 */
internal fun mentions(text: String, phrase: String, allowWordSuffix: Boolean = false): Boolean {
    if (phrase.isEmpty()) return false
    val after = if (allowWordSuffix && phrase.last().isLetter()) "" else "(?![\\p{L}\\p{N}])"
    return Regex("(?<![\\p{L}\\p{N}])${Regex.escape(phrase)}$after", RegexOption.IGNORE_CASE)
        .containsMatchIn(text)
}

/**
 * How many of [mustMention] the answer states, ignoring case. A fact must start a token and,
 * when it ends in a digit, end one: "6" is found in "at most 6 tool calls" but not in
 * "60 seconds". A fact ending in a letter also matches its plural or inflected form.
 */
fun factsHit(mustMention: List<String>, answer: Answer): Int {
    val text = answerText(answer)
    return mustMention.count { mentions(text, it, allowWordSuffix = true) }
}

/**
 * The entries of [mustNotMention] the answer states, ignoring case. A match must stand on
 * its own: "99" is found in "is 99." but not in "1999" or "990".
 */
fun forbiddenMentions(mustNotMention: List<String>, answer: Answer): List<String> {
    val text = answerText(answer)
    return mustNotMention.filter { mentions(text, it) }
}

/** Share of facts hit (1 when none are required); 0 when the answer makes a forbidden claim. */
fun answerScore(factsHit: Int, factsTotal: Int, forbidden: List<String>): Float = when {
    forbidden.isNotEmpty() -> 0f
    factsTotal == 0 -> 1f
    else -> factsHit.toFloat() / factsTotal
}

fun scoreQuestion(question: EvalQuestion, answer: Answer, retrieved: List<RetrievedPiece>): EvalResult =
    EvalResult(
        id = question.id,
        retrievalHit = retrievalHit(question.expectedPieces, retrieved),
        citationValidity = citationValidity(answer),
        factsHit = factsHit(question.mustMention, answer),
        factsTotal = question.mustMention.size,
        durationMs = answer.durationMs,
        answer = answer,
        forbiddenMentioned = forbiddenMentions(question.mustNotMention, answer),
        retrieved = retrieved.map { it.qualifiedName ?: it.name }
    )

/** A question the server could not answer: a miss on every measure. */
fun failedResult(question: EvalQuestion, error: String, durationMs: Long): EvalResult =
    EvalResult(
        id = question.id,
        retrievalHit = false,
        citationValidity = 0f,
        factsHit = 0,
        factsTotal = question.mustMention.size,
        durationMs = durationMs,
        answer = null,
        error = error
    )

private fun EvalResult.answerScore(): Float =
    if (answer == null) 0f else answerScore(factsHit, factsTotal, forbiddenMentioned)

/** The three run numbers, each a mean over questions. */
fun aggregate(startedAt: Long, model: String, results: List<EvalResult>): EvalRun {
    fun mean(f: (EvalResult) -> Float) = if (results.isEmpty()) 0f else results.map(f).sum() / results.size
    return EvalRun(
        startedAt = startedAt,
        model = model,
        results = results,
        retrievalPrecision = mean { if (it.retrievalHit) 1f else 0f },
        citationValidity = mean { it.citationValidity },
        answerScore = mean { it.answerScore() }
    )
}
