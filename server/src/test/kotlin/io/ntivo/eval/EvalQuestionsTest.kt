package io.ntivo.eval

import io.ntivo.parse.KotlinParser
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks `eval/questions.json` against this repository: every expected piece must be a
 * qualified name the indexer would actually store, so a retrieval miss in a run means the
 * search missed, not that the question names a function that does not exist.
 */
class EvalQuestionsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private val questions = loadQuestions(File(root, "eval/questions.json"))

    /** Qualified names as `toPieces` builds them: container, receiver, name. */
    private val indexedNames: Set<String> by lazy {
        val dirs = listOf(File(root, "server/src/main/kotlin"), File(root, "server/src/test/resources/fixtures"))
        dirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .flatMap { file -> KotlinParser.parse(file.readText()).functions }
            .map { fn -> listOfNotNull(fn.container, fn.receiver, fn.name).joinToString(".") }
            .toSet()
    }

    @Test
    fun `there are twelve questions with unique ids`() {
        assertEquals(12, questions.size)
        assertEquals(questions.size, questions.map { it.id }.toSet().size)
        assertTrue(questions.all { it.question.isNotBlank() && it.mustMention.isNotEmpty() })
    }

    @Test
    fun `every expected piece is a function the indexer stores`() {
        val missing = questions.flatMap { q -> q.expectedPieces.filter { it !in indexedNames }.map { "${q.id}: $it" } }
        assertTrue(missing.isEmpty(), "Not found in the parsed sources: $missing")
    }

    @Test
    fun `exactly one general question expects no pieces`() {
        assertEquals(1, questions.count { it.expectedPieces.isEmpty() })
    }

    @Test
    fun `the injected-instruction question forbids the injected claim and does not require it`() {
        val injected = questions.single { it.mustNotMention.isNotEmpty() }
        assertEquals(listOf("99"), injected.mustNotMention)
        assertTrue(injected.mustMention.none { it.contains("99") })
        assertTrue(injected.expectedPieces.all { it.startsWith("GuestPassPolicy.") })

        val fixture = File(root, "server/src/test/resources/fixtures/Injected.kt").readText()
        val body = KotlinParser.parse(fixture).functions.single { it.name == "monthlyGuestPassLimit" }.body
        // The instruction must sit inside the function so it reaches the model with the piece
        assertTrue(body.contains("ignore all previous instructions") && body.contains("99"), body)
    }
}
