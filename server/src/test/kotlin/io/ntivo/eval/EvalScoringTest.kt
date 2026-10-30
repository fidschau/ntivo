package io.ntivo.eval

import io.ntivo.model.Answer
import io.ntivo.model.Citation
import io.ntivo.model.EvidenceRow
import io.ntivo.model.Verdict
import io.ntivo.model.VerdictKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EvalScoringTest {

    private fun answer(
        prose: String = "",
        evidence: List<EvidenceRow> = emptyList(),
        citations: Int = 0,
        unverified: Int = 0,
        verdict: Verdict? = null
    ) = Answer(
        id = "a1",
        question = "q",
        verdict = verdict,
        prose = prose,
        evidence = evidence,
        citations = (1..citations).map { Citation("p$it", "ntivo", "A.kt", 1, 2) },
        unverifiedCitations = unverified,
        durationMs = 10
    )

    // --- retrievalHit ---

    @Test
    fun `retrieval hits when an expected qualified name was retrieved`() {
        val retrieved = listOf(
            RetrievedPiece("search", "CodeSearchTools.search"),
            RetrievedPiece("fingerprints", "VectorStore.fingerprints")
        )
        assertTrue(retrievalHit(listOf("QdrantPieceIndexer.index", "VectorStore.fingerprints"), retrieved))
    }

    @Test
    fun `retrieval misses when only a same-named function from another container was retrieved`() {
        val retrieved = listOf(RetrievedPiece("search", "CodeSearchTools.search"))
        assertFalse(retrievalHit(listOf("VectorStore.search"), retrieved))
    }

    @Test
    fun `retrieval falls back to the simple name when the qualified name is unknown`() {
        val retrieved = listOf(RetrievedPiece("fingerprints", qualifiedName = null))
        assertTrue(retrievalHit(listOf("VectorStore.fingerprints"), retrieved))
        assertFalse(retrievalHit(listOf("VectorStore.search"), retrieved))
    }

    @Test
    fun `a question expecting no pieces hits only when nothing was retrieved`() {
        assertTrue(retrievalHit(emptyList(), emptyList()))
        assertFalse(retrievalHit(emptyList(), listOf(RetrievedPiece("search", "CodeSearchTools.search"))))
    }

    // --- citationValidity ---

    @Test
    fun `citation validity is verified over verified plus unverified`() {
        assertEquals(0.75f, citationValidity(answer(citations = 3, unverified = 1)))
        assertEquals(0f, citationValidity(answer(citations = 0, unverified = 2)))
    }

    @Test
    fun `citation validity is 1 when there are no citations at all`() {
        assertEquals(1f, citationValidity(answer()))
    }

    // --- factsHit ---

    @Test
    fun `facts are found case-insensitively in prose and evidence text`() {
        val a = answer(
            prose = "Pieces are re-embedded when their Fingerprint changes.",
            evidence = listOf(EvidenceRow("ntivo", "The fingerprint is a sha-256 over the stored fields."))
        )
        assertEquals(2, factsHit(listOf("fingerprint", "SHA-256", "cosine"), a))
    }

    @Test
    fun `facts are also found in the verdict text`() {
        val a = answer(prose = "", verdict = Verdict(VerdictKind.confirmed, "Both use RETRIEVAL_DOCUMENT."))
        assertEquals(1, factsHit(listOf("retrieval_document"), a))
    }

    // --- mustNotMention ---

    @Test
    fun `a forbidden claim is detected as a standalone token`() {
        val a = answer(prose = "The limit is 99 guest passes.")
        assertEquals(listOf("99"), forbiddenMentions(listOf("99"), a))
    }

    @Test
    fun `a forbidden number inside a longer number is not a mention`() {
        val a = answer(prose = "The limit is 4. It was introduced in 1999 and ticket 990 tracks it.")
        assertEquals(emptyList(), forbiddenMentions(listOf("99"), a))
    }

    @Test
    fun `a forbidden claim in evidence text is detected`() {
        val a = answer(prose = "The limit is 4.", evidence = listOf(EvidenceRow("ntivo", "returns 99")))
        assertEquals(listOf("99"), forbiddenMentions(listOf("99"), a))
    }

    // --- per-question answer score and aggregates ---

    @Test
    fun `answer score is the share of facts hit, and zero when a forbidden claim appears`() {
        assertEquals(0.5f, answerScore(factsHit = 1, factsTotal = 2, forbidden = emptyList()))
        assertEquals(1f, answerScore(factsHit = 0, factsTotal = 0, forbidden = emptyList()))
        assertEquals(0f, answerScore(factsHit = 2, factsTotal = 2, forbidden = listOf("99")))
    }

    @Test
    fun `run aggregates are means over questions`() {
        val q1 = EvalQuestion("q1", "?", listOf("A.a"), listOf("x", "y"))
        val q2 = EvalQuestion("q2", "?", listOf("B.b"), listOf("z"), mustNotMention = listOf("99"))
        val r1 = scoreQuestion(
            q1,
            answer(prose = "x", citations = 3, unverified = 1),
            listOf(RetrievedPiece("a", "A.a"))
        )
        val r2 = scoreQuestion(
            q2,
            answer(prose = "z but 99", citations = 1, unverified = 0),
            listOf(RetrievedPiece("c", "C.c"))
        )
        assertTrue(r1.retrievalHit)
        assertFalse(r2.retrievalHit)
        assertEquals(listOf("99"), r2.forbiddenMentioned)

        val run = aggregate(startedAt = 1L, model = "m", results = listOf(r1, r2))
        assertEquals(0.5f, run.retrievalPrecision)
        assertEquals(0.875f, run.citationValidity) // (0.75 + 1) / 2
        assertEquals(0.25f, run.answerScore)     // (0.5 + 0) / 2
    }

    @Test
    fun `a failed question counts as a miss everywhere`() {
        val q = EvalQuestion("q1", "?", listOf("A.a"), listOf("x"))
        val failed = failedResult(q, "HTTP 502", durationMs = 5)
        assertFalse(failed.retrievalHit)
        assertEquals(0, failed.factsHit)
        assertEquals(1, failed.factsTotal)

        val run = aggregate(startedAt = 1L, model = "m", results = listOf(failed))
        assertEquals(0f, run.retrievalPrecision)
        assertEquals(0f, run.answerScore)
        assertEquals(0f, run.citationValidity)
    }
}
