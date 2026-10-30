package io.ntivo

import io.ntivo.model.Piece
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Integration test against the local Qdrant (gRPC :6334). Skipped when Qdrant is unreachable. */
class VectorStoreTest {

    private val store = VectorStore()
    private val collection = "test_vectorstore_${UUID.randomUUID().toString().take(8)}"

    private fun piece(id: String, sourceId: String, path: String, name: String, text: String) = Piece(
        id = id, sourceId = sourceId, sourceName = "repo-$sourceId", language = "Kotlin",
        path = path, name = name, qualifiedName = "Repo.$name", startLine = 3, endLine = 7, text = text
    )

    private val s1Load = piece(UUID.randomUUID().toString(), "s1", "Constants.kt", "load", "fun load() = 1")
    private val s1Save = piece(UUID.randomUUID().toString(), "s1", "Constants.kt", "save", "fun save() = 2")
    private val s2Load = piece(UUID.randomUUID().toString(), "s2", "Constants.kt", "load", "fun load() = 3 // — non-ascii")

    private fun point(p: Piece, vector: List<Double>) =
        UUID.fromString(p.id) to (vector to p.payload())

    @BeforeTest
    fun setUp() {
        val reachable = try {
            runBlocking { withTimeout(3_000) { store.collectionExists(collection) } }
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue("Qdrant not reachable on localhost:6334", reachable)
        runBlocking {
            store.ensureCollection(collection, dimensions = 4)
            store.upsertBatch(
                collection,
                listOf(
                    point(s1Load, listOf(1.0, 0.0, 0.0, 0.0)),
                    point(s1Save, listOf(0.9, 0.1, 0.0, 0.0)),
                    point(s2Load, listOf(1.0, 0.0, 0.0, 0.0))
                )
            )
        }
    }

    @AfterTest
    fun tearDown() {
        runCatching { runBlocking { withTimeout(3_000) { store.deleteCollection(collection) } } }
        store.close()
    }

    @Test
    fun `search filtered by source returns only that source`() = runBlocking {
        val all = store.search(collection, listOf(1.0, 0.0, 0.0, 0.0), limit = 10)
        assertEquals(3, all.size)

        val s1Only = store.search(collection, listOf(1.0, 0.0, 0.0, 0.0), limit = 10, sourceIds = listOf("s1"))
        assertEquals(setOf(s1Load.id, s1Save.id), s1Only.map { it.id.uuid }.toSet())
        assertTrue(s1Only.all { it.payloadMap["sourceId"]?.stringValue == "s1" })
    }

    @Test
    fun `search drops results below the minimum score`() = runBlocking {
        // s1Save is at an angle to the query; the two loads score 1.0
        val strict = store.search(collection, listOf(1.0, 0.0, 0.0, 0.0), limit = 10, minScore = 0.999f)
        assertEquals(setOf(s1Load.id, s2Load.id), strict.map { it.id.uuid }.toSet())
    }

    @Test
    fun `empty source filter matches nothing`() = runBlocking {
        val none = store.search(collection, listOf(1.0, 0.0, 0.0, 0.0), limit = 10, sourceIds = emptyList())
        assertTrue(none.isEmpty())
    }

    @Test
    fun `deleteBySource removes exactly that source`() = runBlocking {
        store.deleteBySource(collection, "s2")
        val left = store.search(collection, listOf(1.0, 0.0, 0.0, 0.0), limit = 10)
        assertEquals(setOf(s1Load.id, s1Save.id), left.map { it.id.uuid }.toSet())
        assertNull(store.getPiece(collection, s2Load.id))
    }

    @Test
    fun `getPiece round-trips the payload`() = runBlocking {
        assertEquals(s2Load, store.getPiece(collection, s2Load.id))
        assertEquals(s1Load, store.getPiece(collection, s1Load.id))
        assertNull(store.getPiece(collection, UUID.randomUUID().toString()))
        assertNull(store.getPiece(collection, "not-a-uuid"))
    }

    @Test
    fun `same path and function in two sources are separate points`() = runBlocking {
        assertNotEquals(s1Load.id, s2Load.id)
        assertEquals("s1", store.getPiece(collection, s1Load.id)?.sourceId)
        assertEquals("s2", store.getPiece(collection, s2Load.id)?.sourceId)
    }

    @Test
    fun `fingerprints are read back for existing pieces`() = runBlocking {
        val fps = store.fingerprints(collection, listOf(UUID.fromString(s1Load.id), UUID.randomUUID()))
        assertEquals(mapOf(UUID.fromString(s1Load.id) to s1Load.fingerprint()), fps)
    }
}
