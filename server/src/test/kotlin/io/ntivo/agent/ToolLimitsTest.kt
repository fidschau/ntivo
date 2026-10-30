package io.ntivo.agent

import io.ntivo.model.TraceHop
import io.ntivo.model.TracePiece
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolLimitsTest {

    @Test
    fun `after six recorded calls allow is false and the limit is reported`() {
        val trace = TraceRecorder()
        repeat(6) { i ->
            assertTrue(trace.allow(), "call ${i + 1} should be allowed")
            val result = trace.record("searchCodebase", "q$i") { "ok" to "1 result" }
            assertEquals("ok", result)
        }
        assertFalse(trace.allow())

        val t = trace.toTrace("a1", "explanation", emptyList(), "model")
        assertEquals(6, t.toolCalls.size)
        assertEquals(listOf("toolCalls"), t.limitsHit)
        assertEquals("1 result", t.toolCalls.first().summary)
    }

    @Test
    fun `guarded call returns the limit message once the budget is spent`() {
        val trace = TraceRecorder(maxToolCalls = 1)
        assertEquals("first", trace.guarded("searchCodebase", "a") { "first" to "summary" })
        assertEquals(TOOL_LIMIT_MESSAGE, trace.guarded("searchCodebase", "b") { "second" to "summary" })
        assertEquals(1, trace.toTrace("a1", "", emptyList(), "m").toolCalls.size)
    }

    @Test
    fun `limits are reported once each`() {
        val trace = TraceRecorder()
        trace.limit("time")
        trace.limit("time")
        assertEquals(listOf("time"), trace.toTrace("a1", "", emptyList(), "m").limitsHit)
    }

    @Test
    fun `hop budget stops graph traversal after three hops`() {
        val trace = TraceRecorder()
        repeat(MAX_GRAPH_HOPS) { assertTrue(trace.allowHop()) }
        assertFalse(trace.allowHop())
        assertTrue("hops" in trace.toTrace("a1", "", emptyList(), "m").limitsHit)
    }

    @Test
    fun `retrieved pieces keep their best score and feed the retrieved id set`() {
        val trace = TraceRecorder()
        trace.retrieved(TracePiece("p1", "load", "android-app", "Kotlin", 0.7f))
        trace.retrieved(TracePiece("p1", "load", "android-app", "Kotlin", 0.9f))
        trace.retrieved(TracePiece("p2", "save", "android-app", "Kotlin", 0.65f))
        trace.surfaced("p3")
        trace.hop(TraceHop("Service.run", "calls", "Repo.load", 1.0f))

        assertEquals(setOf("p1", "p2", "p3"), trace.retrievedPieceIds)
        val t = trace.toTrace("a1", "", listOf("ios-app (indexing 40%)"), "m")
        assertEquals(listOf("p1", "p2"), t.pieces.map { it.pieceId })
        assertEquals(0.9f, t.pieces.first().score)
        assertEquals(1, t.hops.size)
        assertEquals(listOf("ios-app (indexing 40%)"), t.unavailable)
    }

    @Test
    fun `failing tool calls are still recorded`() {
        val trace = TraceRecorder()
        runCatching { trace.record<String>("callersOf", "load") { error("boom") } }
        val call = trace.toTrace("a1", "", emptyList(), "m").toolCalls.single()
        assertEquals("callersOf", call.tool)
        assertTrue(call.summary.contains("boom"))
    }
}
