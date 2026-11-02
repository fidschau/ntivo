package io.ntivo.agent

import ai.koog.embeddings.base.Vector
import io.ntivo.CodeGraph
import io.ntivo.EmbeddingTaskType
import io.ntivo.TaskTypeEmbedder
import io.ntivo.VectorStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CodebaseAgentTest {

    private val store = VectorStore()
    private val graph = CodeGraph()

    @AfterTest
    fun tearDown() {
        store.close()
        graph.close()
    }

    private object NoEmbedder : TaskTypeEmbedder {
        override suspend fun embed(text: String, taskType: EmbeddingTaskType) = error("not used")
        override suspend fun embed(text: String): Vector = error("not used")
        override fun diff(embedding1: Vector, embedding2: Vector) = 0.0
    }

    @Test
    fun `the agent gets exactly the four read-only tools`() {
        val trace = TraceRecorder()
        val registry = agentToolRegistry(
            CodeSearchTools(NoEmbedder, store, "c", listOf("s1"), trace),
            GraphTools(graph, listOf("s1"), trace)
        )
        assertEquals(
            setOf("searchCodebase", "callersOf", "calleesOf", "declarationsIn"),
            registry.tools.map { it.name }.toSet()
        )
    }

    @Test
    fun `the models and temperature are pinned`() {
        assertEquals("claude-sonnet-5-5", CLAUDE_ANSWER_MODEL.id)
        assertEquals("gemini-2.5-flash", GEMINI_ANSWER_MODEL.id)
        assertEquals(0.2, ANSWER_TEMPERATURE)
    }

    @Test
    fun `Claude gets the default temperature because it rejects any other`() {
        assertEquals(1.0, temperatureFor(CLAUDE_ANSWER_MODEL))
        assertEquals(1.0, temperatureFor(CLAUDE_ANSWER_MODEL, preferred = 0.0))
        assertEquals(0.2, temperatureFor(GEMINI_ANSWER_MODEL))
    }
}
