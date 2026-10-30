package io.ntivo.sources

import ai.koog.embeddings.base.Vector
import io.ntivo.EmbeddingRateLimited
import io.ntivo.EmbeddingRequestFailed
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Integration test against the local Qdrant (gRPC :6334). Skipped when Qdrant is unreachable. */
class QdrantPieceIndexerTest {

    private val store = VectorStore()
    private val collection = "test_pieceindexer_${UUID.randomUUID().toString().take(8)}"

    /**
     * Four-dimensional vectors derived from the text, recording every embedAll call and task type.
     * The first [failures] embedAll calls throw [failure] instead.
     */
    private class FakeEmbedder(
        failures: Int = 0,
        private val failure: () -> Exception = { EmbeddingRateLimited(retryAfterSeconds = null) }
    ) : TaskTypeEmbedder {
        val calls = AtomicInteger()
        val embedAllCalls = AtomicInteger()
        val failedCalls = AtomicInteger()
        val taskTypes = mutableSetOf<EmbeddingTaskType>()
        private val failuresLeft = AtomicInteger(failures)

        override suspend fun embedAll(texts: List<String>, taskType: EmbeddingTaskType): List<Vector> {
            if (failuresLeft.getAndDecrement() > 0) {
                failedCalls.incrementAndGet()
                throw failure()
            }
            embedAllCalls.incrementAndGet()
            return texts.map { embed(it, taskType) }
        }

        override suspend fun embed(text: String, taskType: EmbeddingTaskType): Vector {
            calls.incrementAndGet()
            synchronized(taskTypes) { taskTypes += taskType }
            val h = text.hashCode()
            return Vector(listOf(1.0, (h and 0xff) / 255.0, ((h shr 8) and 0xff) / 255.0, 0.5))
        }

        override suspend fun embed(text: String): Vector = embed(text, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        override fun diff(embedding1: Vector, embedding2: Vector): Double = 1.0 - embedding1.cosineSimilarity(embedding2)
    }

    /** Records backoff waits instead of sleeping, so retry tests run instantly. */
    private val sleeps = mutableListOf<Long>()
    private val recordSleep: suspend (Long) -> Unit = { sleeps += it }

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
    fun `three pieces are embedded in one embedAll call`(): Unit = runBlocking {
        val embedder = FakeEmbedder()
        assertEquals(3, QdrantPieceIndexer(store, embedder, collection).index("s1", "repo-s1", pieces) { _, _ -> })
        assertEquals(1, embedder.embedAllCalls.get())
    }

    @Test
    fun `progress is reported once per batch`(): Unit = runBlocking {
        val many = (1..40).map { piece("s3", "f$it", "fun f$it() = $it") }
        val progress = mutableListOf<Pair<Int, Int>>()
        val embedder = FakeEmbedder()
        QdrantPieceIndexer(store, embedder, collection, batchSize = 16).index("s3", "repo-s3", many) { d, t -> progress += d to t }
        assertEquals(listOf(16 to 40, 32 to 40, 40 to 40), progress)
        assertEquals(3, embedder.embedAllCalls.get())
    }

    @Test
    fun `a rate limit with Retry-After waits that long and then succeeds`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 1) { EmbeddingRateLimited(retryAfterSeconds = 7) }
        val progress = mutableListOf<Pair<Int, Int>>()
        val indexer = QdrantPieceIndexer(store, embedder, collection, sleep = recordSleep)

        assertEquals(3, indexer.index("s1", "repo-s1", pieces) { d, t -> progress += d to t })

        assertEquals(listOf(7_000L), sleeps)
        assertEquals(1, embedder.failedCalls.get())
        assertEquals(1, embedder.embedAllCalls.get())
        // The wait is reported as progress (nothing done yet) before the batch completes
        assertEquals(listOf(0 to 3, 3 to 3), progress)
    }

    @Test
    fun `a rate limit without a hint backs off exponentially from 2 s with jitter, capped at 60 s`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 7)
        val indexer = QdrantPieceIndexer(store, embedder, collection, sleep = recordSleep)

        assertEquals(3, indexer.index("s1", "repo-s1", pieces) { _, _ -> })

        assertEquals(7, sleeps.size)
        val ceilings = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L)
        sleeps.zip(ceilings).forEach { (slept, ceiling) ->
            assertTrue(slept in ceiling / 2..ceiling, "slept $slept, expected between ${ceiling / 2} and $ceiling")
        }
    }

    @Test
    fun `a persistent rate limit gives up after 8 attempts and rethrows it`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 1_000)
        val indexer = QdrantPieceIndexer(store, embedder, collection, sleep = recordSleep)

        val e = assertFailsWith<EmbeddingRateLimited> { indexer.index("s1", "repo-s1", pieces) { _, _ -> } }

        assertEquals(8, embedder.failedCalls.get())
        assertEquals(7, sleeps.size)
        assertTrue("after 8 attempts" in e.message!!, e.message)
        assertTrue("100" in e.message!! && "per minute" in e.message!!, e.message)
    }

    @Test
    fun `a Retry-After longer than five minutes fails at once`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 1_000) { EmbeddingRateLimited(retryAfterSeconds = 48_957) }
        val indexer = QdrantPieceIndexer(store, embedder, collection, sleep = recordSleep)

        val e = assertFailsWith<EmbeddingRateLimited> { indexer.index("s1", "repo-s1", pieces) { _, _ -> } }

        assertEquals(1, embedder.failedCalls.get())
        assertEquals(0, embedder.embedAllCalls.get())
        assertTrue(sleeps.isEmpty())
        assertEquals(48_957, e.retryAfterSeconds)
        assertTrue("quota" in e.message!!, e.message)
        assertTrue("about 13.6 hours" in e.message!!, e.message)
        assertTrue(e.cause is EmbeddingRateLimited, "original exception kept as the cause")
    }

    @Test
    fun `a Retry-After of 299 seconds still retries as before`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 1) { EmbeddingRateLimited(retryAfterSeconds = 299) }
        val indexer = QdrantPieceIndexer(store, embedder, collection, sleep = recordSleep)

        assertEquals(3, indexer.index("s1", "repo-s1", pieces) { _, _ -> })

        assertEquals(listOf(60_000L), sleeps)
        assertEquals(1, embedder.failedCalls.get())
        assertEquals(1, embedder.embedAllCalls.get())
    }

    @Test
    fun `a Retry-After of a few minutes is described in minutes`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 1_000) { EmbeddingRateLimited(retryAfterSeconds = 301) }
        val indexer = QdrantPieceIndexer(store, embedder, collection, sleep = recordSleep)

        val e = assertFailsWith<EmbeddingRateLimited> { indexer.index("s1", "repo-s1", pieces) { _, _ -> } }

        assertTrue("about 5 minutes" in e.message!!, e.message)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `transient server errors are retried`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 2) { EmbeddingRequestFailed(503, "HTTP 503: unavailable") }
        val indexer = QdrantPieceIndexer(store, embedder, collection, sleep = recordSleep)
        assertEquals(3, indexer.index("s1", "repo-s1", pieces) { _, _ -> })
        assertEquals(2, sleeps.size)
    }

    @Test
    fun `a client error such as a bad key fails at once`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 1_000) { EmbeddingRequestFailed(400, "HTTP 400: API key not valid") }
        val indexer = QdrantPieceIndexer(store, embedder, collection, sleep = recordSleep)
        val e = assertFailsWith<EmbeddingRequestFailed> { indexer.index("s1", "repo-s1", pieces) { _, _ -> } }
        assertEquals(1, embedder.failedCalls.get())
        assertTrue(sleeps.isEmpty())
        assertTrue("API key not valid" in e.message!!)
    }

    @Test
    fun `persistent other failures give up with a clear error`(): Unit = runBlocking {
        val embedder = FakeEmbedder(failures = 1_000) { IllegalStateException("connection reset") }
        val indexer = QdrantPieceIndexer(store, embedder, collection, maxAttempts = 3, sleep = recordSleep)
        val e = assertFailsWith<IllegalStateException> { indexer.index("s1", "repo-s1", pieces) { _, _ -> } }
        assertTrue(e.message!!.startsWith("Could not capture meaning after 3 attempts"), e.message)
        assertTrue("connection reset" in e.message!!)
    }
}
