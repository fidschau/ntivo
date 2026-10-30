package io.ntivo.parse

import io.ntivo.ClassDecl
import io.ntivo.FunctionChunk
import io.ntivo.findClassDecls
import io.ntivo.findFunctions
import io.ntivo.parseKotlin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SwiftParserTest {

    private val source = javaClass.getResource("/fixtures/InviteService.swift")!!.readText()
    private val parsed = SwiftParser.parse(source)
    private val functions = parsed.functions.associateBy { "${it.container}.${it.name}" }
    private val classes = parsed.classes.associateBy { it.qualifiedName }

    @Test
    fun `functions and inits are found with their containers`() {
        assertEquals(
            setOf(
                "InviteLimiting.friendLimit",
                "Invite.isExpired",
                "InviteService.init",
                "InviteService.friendLimit",
                "InviteService.canInvite",
                "InviteService.reset"
            ),
            functions.keys
        )
        parsed.functions.forEach { assertNull(it.receiver) }
    }

    @Test
    fun `parameters are the parenthesised span`() {
        assertEquals("(config: RemoteConfig)", functions.getValue("InviteService.init").parameters)
        assertEquals("()", functions.getValue("InviteService.friendLimit").parameters)
        assertEquals("(_ invites: [Invite])", functions.getValue("InviteService.canInvite").parameters)
    }

    @Test
    fun `line numbers and body text survive multi-byte characters before them`() {
        val limit = functions.getValue("InviteService.friendLimit")
        assertEquals(25, limit.startLine)
        assertEquals(28, limit.endLine)
        assertTrue(limit.body.startsWith("func friendLimit() -> Int {"))
        assertTrue(limit.body.endsWith("}"))
    }

    @Test
    fun `calls are collected from call expressions`() {
        val limitCalls = functions.getValue("InviteService.friendLimit").calls
        assertTrue("int" in limitCalls, "calls were $limitCalls")
        assertEquals(setOf("filter", "isExpired", "Date", "friendLimit"), functions.getValue("InviteService.canInvite").calls.toSet())
        assertEquals(listOf("log"), functions.getValue("InviteService.reset").calls)
        assertEquals(setOf("timeIntervalSince"), functions.getValue("Invite.isExpired").calls.toSet())
    }

    @Test
    fun `classes carry kind and supertypes`() {
        assertEquals(setOf("InviteLimiting", "Invite", "InviteService", "InviteState"), classes.keys)
        assertEquals("protocol", classes.getValue("InviteLimiting").kind)
        assertEquals("class", classes.getValue("InviteService").kind)
        assertEquals(listOf("InviteLimiting"), classes.getValue("InviteService").supertypes)
        assertEquals("struct", classes.getValue("Invite").kind)
        assertEquals("enum", classes.getValue("InviteState").kind)
    }

    @Test
    fun `nested types qualify their container`() {
        val src = """
            struct Outer: Codable, Equatable {
                class Inner: Base<Int> {
                    func go() { helper() }
                }
            }
        """.trimIndent()
        val p = SwiftParser.parse(src)
        assertEquals(setOf("Outer", "Outer.Inner"), p.classes.map { it.qualifiedName }.toSet())
        assertEquals(listOf("Codable", "Equatable"), p.classes.first { it.name == "Outer" }.supertypes)
        assertEquals(listOf("Base"), p.classes.first { it.name == "Inner" }.supertypes)
        assertEquals("Outer.Inner", p.functions.single().container)
    }

    @Test
    fun `parserFor returns the parser for each language`() {
        assertEquals(KotlinParser, parserFor(Language.KOTLIN))
        assertEquals(SwiftParser, parserFor(Language.SWIFT))
        assertEquals(Language.SWIFT, Language.forFile("App/InviteService.swift"))
        assertNull(Language.forFile("README.md"))
    }

    @Test
    fun `KotlinParser matches the original top-level functions`() {
        val src = """
            class UserRepo(private val api: Api) : BaseRepo(), Repo {
                override fun load(id: String): String = normalize(api.fetch(id))
                private fun normalize(s: String) = s.trim()
            }
            fun String.shout(): String = uppercase()
        """.trimIndent()
        val root = parseKotlin(src).rootNode
        val expectedFns = mutableListOf<FunctionChunk>().also { findFunctions(root, src, it) }
        val expectedClasses = mutableListOf<ClassDecl>().also { findClassDecls(root, src, it) }
        val actual = KotlinParser.parse(src)
        assertEquals(expectedFns, actual.functions)
        assertEquals(expectedClasses, actual.classes)
    }
}
