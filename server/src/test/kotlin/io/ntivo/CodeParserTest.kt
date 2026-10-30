package io.ntivo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodeParserTest {

    private val source = """
        package demo

        interface Repo { fun load(id: String): String }

        class UserRepo(private val api: Api) : BaseRepo(), Repo {
            override fun load(id: String): String {
                val raw = api.fetch(id)
                return normalize(raw).trim()
            }

            private fun normalize(s: String): String {
                fun inner() = helper()
                return s.lowercase()
            }

            companion object {
                fun create(): UserRepo = UserRepo(Api())
            }
        }

        object Registry : Repo {
            override fun load(id: String) = id
        }

        fun String.shout(): String = uppercase()
    """.trimIndent()

    private val root = parseKotlin(source).rootNode

    private fun functions() = mutableListOf<FunctionChunk>().also { findFunctions(root, source, it) }
    private fun classes() = mutableListOf<ClassDecl>().also { findClassDecls(root, source, it) }

    @Test
    fun `functions know their enclosing class`() {
        val byName = functions().associateBy { "${it.container}.${it.name}" }
        assertEquals(setOf(
            "Repo.load", "UserRepo.load", "UserRepo.normalize", "UserRepo.inner",
            "UserRepo.create", "Registry.load", "null.shout"
        ), byName.keys)
        assertEquals("String", byName.getValue("null.shout").receiver)
        assertNull(byName.getValue("UserRepo.load").receiver)
    }

    @Test
    fun `calls are collected per function and stop at nested functions`() {
        val byName = functions().associateBy { "${it.container}.${it.name}" }
        assertEquals(setOf("fetch", "normalize", "trim"), byName.getValue("UserRepo.load").calls.toSet())
        // helper() belongs to inner(), not normalize()
        assertEquals(setOf("lowercase"), byName.getValue("UserRepo.normalize").calls.toSet())
        assertEquals(setOf("helper"), byName.getValue("UserRepo.inner").calls.toSet())
        assertEquals(setOf("UserRepo", "Api"), byName.getValue("UserRepo.create").calls.toSet())
    }

    @Test
    fun `classes carry kind and supertypes`() {
        val byName = classes().associateBy { it.qualifiedName }
        assertEquals(setOf("Repo", "UserRepo", "Registry"), byName.keys)
        assertEquals("interface", byName.getValue("Repo").kind)
        assertEquals("object", byName.getValue("Registry").kind)
        assertEquals(listOf("BaseRepo", "Repo"), byName.getValue("UserRepo").supertypes)
        assertEquals(listOf("Repo"), byName.getValue("Registry").supertypes)
    }

    @Test
    fun `non-ascii text before a function does not shift extracted text`() {
        // Tree-sitter offsets are UTF-8 bytes; "—" and "→" are 3 bytes each
        val src = "// parse — chunk → embed\nfun go(x: Int) = x\n"
        val fns = mutableListOf<FunctionChunk>().also { findFunctions(parseKotlin(src).rootNode, src, it) }
        assertEquals("go", fns.single().name)
        assertEquals("fun go(x: Int) = x", fns.single().body)
        assertEquals("(x: Int)", fns.single().parameters)
    }

    @Test
    fun `point id is stable and distinguishes classes`() {
        val loads = functions().filter { it.name == "load" }
        assertEquals(3, loads.map { it.pointId() }.toSet().size)
        assertEquals(functions().first().pointId(), functions().first().pointId())
    }
}
