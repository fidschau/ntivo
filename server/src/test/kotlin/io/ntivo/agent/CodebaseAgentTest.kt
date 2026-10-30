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
    fun `the model and temperature are pinned`() {
        assertEquals("gemini-2.5-flash", ANSWER_MODEL.id)
        assertEquals(0.2, ANSWER_TEMPERATURE)
    }
}
