package io.ntivo.sources

import ai.koog.embeddings.base.Vector
import io.ntivo.EmbeddingTaskType
import io.ntivo.TaskTypeEmbedder
import io.ntivo.VectorStore
import io.ntivo.model.Piece
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Integration test against the local Qdrant (gRPC :6334). Skipped when Qdrant is unreachable. */
class QdrantPieceIndexerTest {

    private val store = VectorStore()
    private val collection = "test_pieceindexer_${UUID.randomUUID().toString().take(8)}"

    /** Four-dimensional vectors derived from the text, recording every call and task type. */
    private class FakeEmbedder(private val failures: Int = 0) : TaskTypeEmbedder {
        val calls = AtomicInteger()
        val taskTypes = mutableSetOf<EmbeddingTaskType>()
        private val failuresLeft = AtomicInteger(failures)

        override suspend fun embed(text: String, taskType: EmbeddingTaskType): Vector {
            if (failuresLeft.getAndDecrement() > 0) throw IllegalStateException("429 Too Many Requests")
            calls.incrementAndGet()
            synchronized(taskTypes) { taskTypes += taskType }
            val h = text.hashCode()
            return Vector(listOf(1.0, (h and 0xff) / 255.0, ((h shr 8) and 0xff) / 255.0, 0.5))
        }

        override suspend fun embed(text: String): Vector = embed(text, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        override fun diff(embedding1: Vector, embedding2: Vector): Double = 1.0 - embedding1.cosineSimilarity(embedding2)
    }

    private fun piece(sourceId: String, name: String, text: String, startLine: Int = 1) = Piece(
        id = UUID.nameUUIDFromBytes("$sourceId|Constants.kt|$name".toByteArray()).toString(),
        sourceId = sourceId, sourceName = "repo-$sourceId", language = "Kotlin", path = "Constants.kt",
        name = name, qualifiedName = "Constants.$name", startLine = startLine, endLine = startLine + 2, text = text
    )

    private val pieces = listOf(
        piece("s1", "load", "fun load() = 1"),
        piece("s1", "save", "fun save() = 2"),
        piece("s2", "load", "fun load() = 3")
    )

    @BeforeTest
    fun setUp() {
        val reachable = try {
            runBlocking { withTimeout(3_000) { store.collectionExists(collection) } }
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue("Qdrant not reachable on localhost:6334", reachable)
    }

    @AfterTest
    fun tearDown() {
        runCatching { runBlocking { withTimeout(3_000) { store.deleteCollection(collection) } } }
        store.close()
    }

    @Test
    fun `indexes pieces as retrieval documents and skips unchanged ones on re-index`(): Unit = runBlocking {
        val embedder = FakeEmbedder()
        val indexer = QdrantPieceIndexer(store, embedder, collection)

        val firstProgress = mutableListOf<Pair<Int, Int>>()
        val first = indexer.index("s1", "repo-s1", pieces) { d, t -> firstProgress += d to t }
        assertEquals(3, first)
        assertEquals(3, embedder.calls.get())
        assertEquals(setOf(EmbeddingTaskType.RETRIEVAL_DOCUMENT), embedder.taskTypes)
        assertEquals(listOf(3 to 3), firstProgress)

        val secondProgress = mutableListOf<Pair<Int, Int>>()
        val second = indexer.index("s1", "repo-s1", pieces) { d, t -> secondProgress += d to t }
        assertEquals(0, second)
        assertEquals(3, embedder.calls.get())
        assertEquals(listOf(3 to 3), secondProgress)

        // Stored payloads round-trip, and the same path in two sources stays two pieces
        assertEquals(pieces[0], store.getPiece(collection, pieces[0].id))
        assertEquals(pieces[2], store.getPiece(collection, pieces[2].id))
    }

    @Test
    fun `a moved function is stored again so its line numbers stay right`(): Unit = runBlocking {
        val embedder = FakeEmbedder()
        val indexer = QdrantPieceIndexer(store, embedder, collection)
        indexer.index("s1", "repo-s1", pieces) { _, _ -> }

        val moved = pieces[0].copy(startLine = 40, endLine = 42)
        assertEquals(1, indexer.index("s1", "repo-s1", listOf(moved) + pieces.drop(1)) { _, _ -> })
        assertEquals(40, store.getPiece(collection, moved.id)?.startLine)
    }

    @Test
    fun `progress is reported once per batch of 16`(): Unit = runBlocking {
        val many = (1..40).map { piece("s3", "f$it", "fun f$it() = $it") }
        val progress = mutableListOf<Pair<Int, Int>>()
        QdrantPieceIndexer(store, FakeEmbedder(), collection).index("s3", "repo-s3", many) { d, t -> progress += d to t }
        assertEquals(listOf(16 to 40, 32 to 40, 40 to 40), progress)
    }

    @Test
    fun `transient embedding failures are retried`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 2)
        val indexer = QdrantPieceIndexer(store, embedder, collection, retryDelayMs = 1)
        assertEquals(3, indexer.index("s1", "repo-s1", pieces) { _, _ -> })
        assertTrue(embedder.calls.get() >= 3)
    }
}
