package io.ntivo.parse

import io.ntivo.ClassDecl
import io.ntivo.FunctionChunk
import io.ntivo.SourceBytes
import io.ntivo.findChildByType
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterSwift

/**
 * Swift, via the Tree-sitter Swift grammar.
 *
 * Grammar mapping (checked against the tree of `fixtures/InviteService.swift`):
 * - `function_declaration`, `init_declaration`, `deinit_declaration` and
 *   `protocol_function_declaration` become [FunctionChunk]s. Swift has no
 *   receiver syntax, so `receiver` is always null.
 * - `class_declaration` covers class, struct, enum, actor and extension; the
 *   first keyword child gives the kind. `protocol_declaration` has kind `protocol`.
 * - Extensions are not emitted as [ClassDecl]s (they would duplicate the type they
 *   extend and make inheritance edges ambiguous), but their functions get the
 *   extended type as container.
 * - Supertypes come from `inheritance_specifier`; calls from `call_expression`
 *   whose callee is a `simple_identifier` or `navigation_expression`.
 */
object SwiftParser : LanguageParser {
    override val language = Language.SWIFT

    private val FUNCTION_LIKE = setOf(
        "function_declaration", "init_declaration", "deinit_declaration", "protocol_function_declaration"
    )
    private val TYPE_LIKE = setOf("class_declaration", "protocol_declaration")
    private val TYPE_KEYWORDS = setOf("class", "struct", "enum", "actor", "extension")

    override fun parse(source: String): ParsedFile {
        val parser = TSParser()
        parser.setLanguage(TreeSitterSwift())
        val root = parser.parseString(null, source).rootNode
        val bytes = SourceBytes(source)
        val functions = mutableListOf<FunctionChunk>()
        val classes = mutableListOf<ClassDecl>()
        walk(root, bytes, container = null, functions, classes)
        return ParsedFile(functions, classes)
    }

    private fun walk(
        node: TSNode,
        source: SourceBytes,
        container: String?,
        functions: MutableList<FunctionChunk>,
        classes: MutableList<ClassDecl>
    ) {
        var childContainer = container
        when (node.type) {
            in FUNCTION_LIKE -> functions += function(node, source, container)
            in TYPE_LIKE -> {
                val kind = typeKind(node)
                val name = typeName(node, kind, source)
                if (name != null) {
                    // An extension of `Foo` inside `Outer` still extends the type named `Foo`
                    childContainer = if (kind == "extension") name else container?.let { "$it.$name" } ?: name
                    if (kind != "extension") {
                        classes += ClassDecl(
                            name = name,
                            kind = kind,
                            supertypes = supertypes(node, source),
                            container = container,
                            startLine = node.startPoint.row + 1,
                            endLine = node.endPoint.row + 1
                        )
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            walk(node.getChild(i), source, childContainer, functions, classes)
        }
    }

    private fun function(node: TSNode, source: SourceBytes, container: String?): FunctionChunk {
        val name = when (node.type) {
            "init_declaration" -> "init"
            "deinit_declaration" -> "deinit"
            else -> findChildByType(node, "simple_identifier")?.let { source.text(it) } ?: "<anonymous>"
        }
        val calls = linkedSetOf<String>()
        findChildByType(node, "function_body")?.let { collectCalls(it, source, calls) }
        return FunctionChunk(
            name = name,
            receiver = null,
            parameters = parameters(node, source),
            startLine = node.startPoint.row + 1,
            endLine = node.endPoint.row + 1,
            body = source.text(node),
            container = container,
            calls = calls.toList()
        )
    }

    /** Text from the declaration's own `(` to its matching `)`, e.g. `(_ invites: [Invite])`. */
    private fun parameters(node: TSNode, source: SourceBytes): String {
        var open: TSNode? = null
        var close: TSNode? = null
        for (i in 0 until node.childCount) {
            val c = node.getChild(i)
            if (c.type == "(" && open == null) open = c
            if (c.type == ")" && open != null) close = c
            if (c.type == "function_body") break
        }
        if (open == null || close == null) return "()"
        return source.slice(open.startByte, close.endByte)
    }

    private fun typeKind(node: TSNode): String {
        if (node.type == "protocol_declaration") return "protocol"
        for (i in 0 until node.childCount) {
            val t = node.getChild(i).type
            if (t in TYPE_KEYWORDS) return t
        }
        return "class"
    }

    /** `type_identifier` for declarations; for extensions the last name in the extended `user_type`. */
    private fun typeName(node: TSNode, kind: String, source: SourceBytes): String? {
        findChildByType(node, "type_identifier")?.let { return source.text(it) }
        if (kind == "extension") {
            val userType = findChildByType(node, "user_type") ?: return null
            return lastTypeIdentifier(userType, source)
        }
        return null
    }

    private fun supertypes(node: TSNode, source: SourceBytes): List<String> {
        val names = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val spec = node.getChild(i)
            if (spec.type != "inheritance_specifier") continue
            val userType = findChildByType(spec, "user_type") ?: continue
            lastTypeIdentifier(userType, source)?.let { names += it }
        }
        return names
    }

    /** `Foundation.Data` → "Data", `Base<Int>` → "Base". */
    private fun lastTypeIdentifier(userType: TSNode, source: SourceBytes): String? {
        var last: TSNode? = null
        for (j in 0 until userType.childCount) {
            val c = userType.getChild(j)
            if (c.type == "type_identifier") last = c
        }
        return last?.let { source.text(it) }
    }

    /** Called names under [node], stopping at nested functions and types: their calls are their own. */
    private fun collectCalls(node: TSNode, source: SourceBytes, calls: MutableSet<String>) {
        if (node.type in FUNCTION_LIKE || node.type in TYPE_LIKE) return
        if (node.type == "call_expression" && node.childCount > 0) {
            calleeName(node.getChild(0), source)?.let { calls += it }
        }
        for (i in 0 until node.childCount) {
            collectCalls(node.getChild(i), source, calls)
        }
    }

    /** `foo()` → "foo", `a.b.foo()` → "foo". Anything else (closures, subscripts) → null. */
    private fun calleeName(callee: TSNode, source: SourceBytes): String? = when (callee.type) {
        "simple_identifier" -> source.text(callee)
        "navigation_expression" -> {
            val suffix = callee.getChild(callee.childCount - 1)
            if (suffix.type == "navigation_suffix") findChildByType(suffix, "simple_identifier")?.let { source.text(it) }
            else null
        }
        else -> null
    }
}
