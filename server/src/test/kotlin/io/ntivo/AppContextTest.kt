package io.ntivo

import io.ntivo.agent.CLAUDE_ANSWER_MODEL
import io.ntivo.agent.GEMINI_ANSWER_MODEL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Provider selection in [AppContext.fromEnvironment], driven by a fake environment. */
class AppContextTest {

    private fun context(vararg env: Pair<String, String>): AppContext {
        val vars = env.toMap()
        return AppContext.fromEnvironment(Files.createTempDirectory("ntivo-ctx").toFile()) { vars[it] }
    }

    @Test
    fun `defaults to local Ollama embeddings and no answer model`() {
        val ctx = context()
        try {
            val embedder = assertIs<OllamaEmbedder>(ctx.embedder)
            assertEquals(OllamaEmbedder.DEFAULT_MODEL, embedder.model)
            assertEquals("chunks_sportcity_qwen3_embedding_0_6b", ctx.collection)
            assertNull(ctx.executor)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `an Anthropic key answers with Claude, even when a Gemini key is set`() {
        val ctx = context("NTIVO_ANTHROPIC_API_KEY" to "a", "NTIVO_GEMINI_API_KEY" to "g")
        try {
            assertNotNull(ctx.executor)
            assertEquals(CLAUDE_ANSWER_MODEL, ctx.answerModel)
            assertIs<OllamaEmbedder>(ctx.embedder)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `Gemini answers and embeds when asked to`() {
        val ctx = context("NTIVO_GEMINI_API_KEY" to "g", "NTIVO_EMBEDDER" to "gemini")
        try {
            assertEquals(GEMINI_ANSWER_MODEL, ctx.answerModel)
            assertIs<GeminiEmbedder>(ctx.embedder)
            assertEquals("chunks_sportcity_gemini_embedding_001", ctx.collection)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `the Ollama model picks the collection, so models never share vectors`() {
        val ctx = context("NTIVO_OLLAMA_EMBED_MODEL" to "qwen3-embedding:4b")
        try {
            assertEquals("chunks_sportcity_qwen3_embedding_4b", ctx.collection)
        } finally {
            ctx.close()
        }
    }

    @Test
    fun `an unknown embedder is a startup error`() {
        assertFailsWith<IllegalStateException> { context("NTIVO_EMBEDDER" to "openai") }
    }

    @Test
    fun `the Anthropic client knows the pinned Claude id`() {
        assertEquals(CLAUDE_ANSWER_MODEL.id, AppContext.anthropicSettings().modelVersionsMap[CLAUDE_ANSWER_MODEL])
        assertTrue(AppContext.anthropicSettings().modelVersionsMap.size > 1)
    }
}
