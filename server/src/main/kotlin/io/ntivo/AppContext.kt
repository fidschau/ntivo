package io.ntivo

import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import io.ntivo.sources.SourceStore
import java.io.File

/**
 * Long-lived services, built once in `Application.module()` and passed to every
 * `configureX(ctx)`. [embedder] and [executor] are null when no model key is set.
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
    val orgId: String = DEFAULT_ORG_ID
) {
    /** Qdrant collection for every piece of this org. */
    val collection: String get() = "chunks_$orgId"

    /** Working copies of fetched sources, one directory per source id. */
    val reposDir: File get() = File(dataDir, "repos")

    /** Persisted answers and traces. */
    val tracesDir: File get() = File(dataDir, "traces")

    fun close() {
        vectorStore.close()
        codeGraph.close()
        (embedder as? GeminiEmbedder)?.close()
    }

    companion object {
        const val DEFAULT_ORG_ID = "sportcity"

        /** Builds the context from environment variables (see CLAUDE.md for the names). */
        fun fromEnvironment(dataDir: File = defaultDataDir()): AppContext {
            val geminiKey = System.getenv("NTIVO_GEMINI_API_KEY")?.takeIf { it.isNotBlank() }
            return AppContext(
                sources = SourceStore(File(dataDir, "sources.json")),
                events = EventBus(),
                vectorStore = VectorStore(),
                codeGraph = CodeGraph(),
                embedder = geminiKey?.let { GeminiEmbedder(it) },
                executor = geminiKey?.let { MultiLLMPromptExecutor(GoogleLLMClient(it)) },
                dataDir = dataDir
            )
        }

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
