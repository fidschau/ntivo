package io.ntivo.sources

import io.ntivo.FunctionChunk
import io.ntivo.model.Source
import io.ntivo.model.SourceKind
import io.ntivo.model.SourceState
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceStoreTest {

    private fun tempFile(): File = Files.createTempFile("sources", ".json").toFile().also { it.deleteOnExit() }

    @Test
    fun `put then get round-trips through the file`() {
        val file = tempFile()
        SourceStore(file).put(Source("abc", "android-app", SourceKind.github, SourceState.indexing))
        assertEquals("android-app", SourceStore(file).get("abc")?.name)
    }

    @Test
    fun `update replaces atomically`() {
        val file = tempFile()
        val store = SourceStore(file)
        store.put(Source("abc", "android-app", SourceKind.github, SourceState.indexing))
        val updated = store.update("abc") { it.copy(state = SourceState.ready, pieces = 12) }
        assertEquals(SourceState.ready, updated.state)
        assertEquals(SourceState.ready, store.get("abc")?.state)
        assertEquals(12, SourceStore(file).get("abc")?.pieces)
    }

    @Test
    fun `remove deletes and persists`() {
        val file = tempFile()
        val store = SourceStore(file)
        store.put(Source("a", "one", SourceKind.upload, SourceState.ready))
        store.put(Source("b", "two", SourceKind.upload, SourceState.ready))
        store.remove("a")
        assertNull(SourceStore(file).get("a"))
        assertEquals(listOf("b"), SourceStore(file).all().map { it.id })
    }

    @Test
    fun `missing file starts empty and parent dirs are created on write`() {
        val dir = Files.createTempDirectory("store").toFile()
        val file = File(dir, "nested/sources.json")
        val store = SourceStore(file)
        assertTrue(store.all().isEmpty())
        store.put(Source("abc", "x", SourceKind.github, SourceState.ready))
        assertTrue(file.isFile)
        dir.deleteRecursively()
    }

    @Test
    fun `piece ids differ across sources for the same path and function`() {
        val fn = FunctionChunk("load", null, "(id: String)", 1, 3, "fun load(id: String) {}")
        assertNotEquals(pieceId("s1", "Repo.kt", fn), pieceId("s2", "Repo.kt", fn))
        assertEquals(pieceId("s1", "Repo.kt", fn), pieceId("s1", "Repo.kt", fn))
    }

    @Test
    fun `node ids carry the source id`() {
        assertEquals("FUNCTION:s1:Repo.kt#Repo.load", nodeId("s1", "FUNCTION", "Repo.kt", "Repo.load"))
    }

    @Test
    fun `new source ids are 12 hex chars`() {
        val id = newSourceId()
        assertEquals(12, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }
}
