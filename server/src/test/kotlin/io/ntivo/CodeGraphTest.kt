package io.ntivo

import io.ntivo.parse.KotlinParser
import io.ntivo.parse.Language
import io.ntivo.sources.ParsedSourceFile
import io.ntivo.sources.pieceId
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Integration test against the local Neo4j (bolt :7687). Skipped when Neo4j is unreachable. */
class CodeGraphTest {

    private val graph = CodeGraph()
    private val run = UUID.randomUUID().toString().take(8)
    private val s1 = "t1$run"
    private val s2 = "t2$run"

    private val repoSource = """
        package demo

        class Repo {
            fun load(id: String): String = id
        }

        class Service(private val repo: Repo) : Base() {
            fun run() = repo.load("x")
        }

        open class Base
    """.trimIndent()

    private fun file(path: String, source: String) =
        ParsedSourceFile(path, Language.KOTLIN, KotlinParser.parse(source))

    private var reachable = false

    @BeforeTest
    fun setUp() {
        reachable = graph.isReachable()
        assumeTrue("Neo4j not reachable on bolt://localhost:7687", reachable)
    }

    @AfterTest
    fun tearDown() {
        // Skip cleanup when unreachable: the driver would retry each query for ~30 s
        if (reachable) {
            graph.deleteSource(s1)
            graph.deleteSource(s2)
        }
        graph.close()
    }

    private fun rebuildBoth() = runBlocking {
        graph.rebuild(s1, "android-app", listOf(file("app/Repo.kt", repoSource)), { _, _ -> })
        graph.rebuild(s2, "ios-app", listOf(file("app/Repo.kt", repoSource)), { _, _ -> })
    }

    @Test
    fun `same path in two sources gives different node ids`() {
        rebuildBoth()
        val a = graph.declarationsIn("Repo.kt", listOf(s1)).single { it.name == "load" }
        val b = graph.declarationsIn("Repo.kt", listOf(s2)).single { it.name == "load" }
        assertNotEquals(a.id, b.id)
        assertTrue(a.id.contains(s1))
        assertEquals("android-app", a.sourceName)
        assertEquals("ios-app", b.sourceName)
    }

    @Test
    fun `function nodes carry the piece id of the matching Qdrant point`() {
        rebuildBoth()
        val load = KotlinParser.parse(repoSource).functions.single { it.name == "load" }
        val node = graph.declarationsIn("app/Repo.kt", listOf(s1)).single { it.name == "load" }
        assertEquals(pieceId(s1, "app/Repo.kt", load), node.pieceId)
        assertEquals("Function", node.label)
        assertEquals("Repo.load", node.qualifiedName)
    }

    @Test
    fun `same-signature functions in one file get distinct piece ids matching the pieces`() = runBlocking {
        val source = """
            package demo

            class Trees {
                val first = object : Runnable {
                    override fun run() { println("a") }
                }
                val second = object : Runnable {
                    override fun run() { println("b") }
                }
            }
        """.trimIndent()
        val parsed = file("app/Trees.kt", source)
        val runs = parsed.parsed.functions.filter { it.name == "run" }
        assertEquals(2, runs.size)
        // Precondition: the parser gives both the same signature, so the old id collided
        assertEquals(pieceId(s1, "app/Trees.kt", runs[0]), pieceId(s1, "app/Trees.kt", runs[1]))

        graph.rebuild(s1, "timber", listOf(parsed), { _, _ -> })
        val nodes = graph.declarationsIn("app/Trees.kt", listOf(s1)).filter { it.name == "run" }
        val pieces = io.ntivo.sources.toPieces(s1, "timber", parsed).filter { it.name == "run" }
        assertEquals(2, nodes.size)
        assertEquals(2, nodes.mapNotNull { it.pieceId }.toSet().size)
        assertEquals(pieces.map { it.id }.toSet(), nodes.mapNotNull { it.pieceId }.toSet())
        // Each node's piece is the one at the same location
        for (node in nodes) {
            assertEquals(pieces.single { it.id == node.pieceId }.startLine, node.startLine)
        }
    }

    @Test
    fun `callers are scoped to the given sources and resolve within the source first`() {
        rebuildBoth()
        val callers = graph.callersOf("load", listOf(s1))
        assertEquals(1, callers.size)
        val edge = callers.single()
        assertEquals("run", edge.from.name)
        assertEquals("CALLS", edge.relation)
        assertEquals("android-app", edge.from.sourceName)
        assertEquals("android-app", edge.to.sourceName)
        // Both sources declare load(), but the local one wins outright
        assertEquals(1.0f, edge.confidence)

        val both = graph.callersOf("load", listOf(s1, s2))
        assertEquals(setOf("android-app", "ios-app"), both.map { it.from.sourceName }.toSet())
        assertTrue(both.all { it.from.sourceName == it.to.sourceName })
    }

    @Test
    fun `callees of a function`() {
        rebuildBoth()
        val callees = graph.calleesOf("run", listOf(s2))
        assertEquals(listOf("load"), callees.map { it.to.name })
        assertEquals("ios-app", callees.single().to.sourceName)
    }

    @Test
    fun `calls fall back to another source when the name is not declared locally`() = runBlocking {
        graph.rebuild(s2, "shared", listOf(file("Helpers.kt", "fun sharedHelper() = 1")), { _, _ -> })
        graph.rebuild(s1, "app", listOf(file("Main.kt", "fun main() { sharedHelper() }")), { _, _ -> })
        val edge = graph.callersOf("sharedHelper", listOf(s1, s2)).single()
        assertEquals("app", edge.from.sourceName)
        assertEquals("shared", edge.to.sourceName)
        assertTrue(graph.callersOf("sharedHelper", listOf(s2)).isEmpty())
    }

    @Test
    fun `rebuilding one source leaves the other untouched`() = runBlocking {
        rebuildBoth()
        val s2Before = graph.countNodes(s2)
        assertTrue(s2Before > 0)
        val stats = graph.rebuild(s1, "android-app", listOf(file("Other.kt", "class Lone { fun a() = b()\n fun b() = 1 }")), { _, _ -> })
        assertEquals(s2Before, graph.countNodes(s2))
        assertEquals(GraphStatsExpect(classes = 1, functions = 2, callEdges = 1), GraphStatsExpect(stats.classes, stats.functions, stats.callEdges))
        // The old Repo.kt nodes of s1 are gone
        assertTrue(graph.declarationsIn("Repo.kt", listOf(s1)).isEmpty())
    }

    @Test
    fun `inherits edges and stats are reported`() = runBlocking {
        val stats = graph.rebuild(s1, "android-app", listOf(file("app/Repo.kt", repoSource)), { _, _ -> })
        assertEquals(3, stats.classes)
        assertEquals(2, stats.functions)
        assertEquals(1, stats.callEdges)
        assertEquals(1, stats.inheritsEdges)
    }

    @Test
    fun `progress reaches the total`() = runBlocking {
        val seen = mutableListOf<Pair<Int, Int>>()
        graph.rebuild(s1, "x", listOf(file("A.kt", "fun a() = 1"), file("B.kt", "fun b() = a()")), { d, t -> seen += d to t })
        assertEquals(2 to 2, seen.last())
    }

    @Test
    fun `declarations are limited`() {
        rebuildBoth()
        assertEquals(2, graph.declarationsIn("Repo.kt", listOf(s1, s2), limit = 2).size)
    }

    private data class GraphStatsExpect(val classes: Int, val functions: Int, val callEdges: Int)
}
