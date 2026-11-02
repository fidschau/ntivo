package io.ntivo

import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import io.ntivo.agent.CLAUDE_ANSWER_MODEL
import io.ntivo.agent.GEMINI_ANSWER_MODEL
import io.ntivo.sources.SourceStore
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Long-lived services, built once in `Application.module()` and passed to every
 * `configureX(ctx)`. [embedder] is null when the configured one has no key, [executor]
 * when no answer model key is set.
 *
 * All sources share one Qdrant collection ([collection]) and one Neo4j database;
 * pieces and nodes carry their `sourceId` to keep repositories apart.
 */
class AppContext(
    val sources: SourceStore,
    val events: EventBus,
    val vectorStore: VectorStore,
    val codeGraph: CodeGraph,
    val embedder: TaskTypeEmbedder?,
    val executor: PromptExecutor?,
    val dataDir: File,
    /** Tenant id. The demo runs a single workspace. */
    val orgId: String = DEFAULT_ORG_ID,
    /**
     * Qdrant collection for every piece of this org. `fromEnvironment` names it after the
     * embedding model ([collectionFor]), because vectors from different models can't be mixed.
     */
    val collection: String = "chunks_$orgId",
    /** The model that answers questions; must match the [executor]'s provider. */
    val answerModel: LLModel = CLAUDE_ANSWER_MODEL
) {
    /** Working copies of fetched sources, one directory per source id. */
    val reposDir: File get() = File(dataDir, "repos")

    /** Persisted answers and traces. */
    val tracesDir: File get() = File(dataDir, "traces")

    fun close() {
        vectorStore.close()
        codeGraph.close()
        (embedder as? AutoCloseable)?.close()
    }

    companion object {
        const val DEFAULT_ORG_ID = "sportcity"

        private val log = LoggerFactory.getLogger(AppContext::class.java)

        /**
         * Builds the context from environment variables (see CLAUDE.md for the names).
         *
         * Embeddings: `NTIVO_EMBEDDER` is `ollama` (default, local) or `gemini`.
         * Answers: Claude when `NTIVO_ANTHROPIC_API_KEY` is set, else Gemini when
         * `NTIVO_GEMINI_API_KEY` is set, else none.
         */
        fun fromEnvironment(dataDir: File = defaultDataDir(), env: (String) -> String? = System::getenv): AppContext {
            fun value(name: String) = env(name)?.takeIf { it.isNotBlank() }
            val geminiKey = value("NTIVO_GEMINI_API_KEY")
            val anthropicKey = value("NTIVO_ANTHROPIC_API_KEY")

            val embedderChoice = (value("NTIVO_EMBEDDER") ?: "ollama").lowercase()
            val (embedder, embeddingModel) = when (embedderChoice) {
                "gemini" -> geminiKey?.let { GeminiEmbedder(it) } to GEMINI_EMBEDDING_MODEL
                "ollama" -> {
                    val model = value("NTIVO_OLLAMA_EMBED_MODEL") ?: OllamaEmbedder.DEFAULT_MODEL
                    OllamaEmbedder(model, value("NTIVO_OLLAMA_URL") ?: OllamaEmbedder.DEFAULT_URL) to model
                }
                else -> error("NTIVO_EMBEDDER must be 'ollama' or 'gemini', not '$embedderChoice'")
            }

            val (executor, answerModel) = when {
                anthropicKey != null -> MultiLLMPromptExecutor(AnthropicLLMClient(anthropicKey, anthropicSettings())) to CLAUDE_ANSWER_MODEL
                geminiKey != null -> MultiLLMPromptExecutor(GoogleLLMClient(geminiKey)) to GEMINI_ANSWER_MODEL
                else -> null to CLAUDE_ANSWER_MODEL
            }

            val collection = collectionFor(DEFAULT_ORG_ID, embeddingModel)
            log.info(
                "Embeddings: {} ({}), collection {}. Answers: {}",
                embedderChoice, embeddingModel, collection, if (executor != null) answerModel.id else "no model key"
            )
            return AppContext(
                sources = SourceStore(File(dataDir, "sources.json")),
                events = EventBus(),
                vectorStore = VectorStore(),
                codeGraph = CodeGraph(),
                embedder = embedder,
                executor = executor,
                dataDir = dataDir,
                collection = collection,
                answerModel = answerModel
            )
        }

        const val GEMINI_EMBEDDING_MODEL = "gemini-embedding-001"

        /** `chunks_sportcity_qwen3_embedding_0_6b` for `qwen3-embedding:0.6b`. */
        fun collectionFor(orgId: String, embeddingModel: String): String =
            "chunks_${orgId}_" + embeddingModel.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')

        /** Koog's known Claude ids plus [CLAUDE_ANSWER_MODEL], which Koog 1.3.0 predates. */
        fun anthropicSettings() = AnthropicClientSettings(
            modelVersionsMap = AnthropicClientSettings().modelVersionsMap + (CLAUDE_ANSWER_MODEL to CLAUDE_ANSWER_MODEL.id)
        )

        /**
         * `NTIVO_DATA_DIR` if set, else `data/` at the repository root. `./gradlew :server:run`
         * starts in `server/`, so walk up to the directory holding `settings.gradle.kts`.
         */
        fun defaultDataDir(): File {
            System.getenv("NTIVO_DATA_DIR")?.takeIf { it.isNotBlank() }?.let { return File(it) }
            val cwd = File("").absoluteFile
            val root = generateSequence(cwd) { it.parentFile }
                .take(3)
                .firstOrNull { File(it, "settings.gradle.kts").isFile }
                ?: cwd
            return File(root, "data")
        }
    }
}
