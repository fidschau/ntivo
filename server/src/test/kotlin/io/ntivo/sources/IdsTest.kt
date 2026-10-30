package io.ntivo.sources

import io.ntivo.FunctionChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class IdsTest {

    private val load = FunctionChunk("load", null, "(id: String)", 2, 4, "fun load(id: String) = 1", container = "Repo")

    @Test
    fun `ids for unique functions are unchanged by the ordinal overload`() {
        // Value of the original formula for this function: existing Qdrant points and graph nodes keep their ids
        val legacy = "cd3ab299-bbb5-3f30-9e3c-90531caa5dd5"
        assertEquals(legacy, pieceId("s1", "app/Repo.kt", load))
        assertEquals(legacy, pieceId("s1", "app/Repo.kt", load, 0))
    }

    @Test
    fun `a positive ordinal gives a different, stable id`() {
        val first = pieceId("s1", "app/Repo.kt", load, 0)
        val second = pieceId("s1", "app/Repo.kt", load, 1)
        val third = pieceId("s1", "app/Repo.kt", load, 2)
        assertEquals(3, setOf(first, second, third).size)
        assertEquals(second, pieceId("s1", "app/Repo.kt", load, 1))
    }

    @Test
    fun `ordinals number same-signature functions in source order`() {
        val run1 = FunctionChunk("run", null, "()", 3, 3, "override fun run() {}", container = "Main")
        val other = FunctionChunk("other", null, "()", 5, 5, "fun other() {}", container = "Main")
        val run2 = FunctionChunk("run", null, "()", 8, 8, "override fun run() { x() }", container = "Main")
        val runOtherClass = FunctionChunk("run", null, "()", 12, 12, "fun run() {}", container = "Other")
        val run3 = FunctionChunk("run", null, "()", 15, 15, "override fun run() { y() }", container = "Main")
        val functions = listOf(run1, other, run2, runOtherClass, run3)

        assertEquals(listOf(0, 0, 1, 0, 2), pieceOrdinals(functions))

        val ids = pieceIds("s1", "app/Main.kt", functions)
        assertEquals(functions.size, ids.toSet().size)
        assertEquals(pieceId("s1", "app/Main.kt", run1), ids[0])
        assertEquals(pieceId("s1", "app/Main.kt", run2, 1), ids[2])
        assertEquals(pieceId("s1", "app/Main.kt", run3, 2), ids[4])
    }

    @Test
    fun `parameters and receivers keep functions apart without ordinals`() {
        val a = FunctionChunk("f", null, "(a: Int)", 1, 1, "")
        val b = FunctionChunk("f", null, "(a: String)", 2, 2, "")
        val c = FunctionChunk("f", "String", "(a: Int)", 3, 3, "")
        assertEquals(listOf(0, 0, 0), pieceOrdinals(listOf(a, b, c)))
        assertNotEquals(pieceId("s1", "F.kt", a), pieceId("s1", "F.kt", b))
    }
}
