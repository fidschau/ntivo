package io.ntivo

import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSTree
import org.treesitter.TreeSitterKotlin

/** Parse Kotlin source into a Tree-sitter syntax tree. */
fun parseKotlin(source: String): TSTree {
    val parser = TSParser()
    parser.setLanguage(TreeSitterKotlin())
    return parser.parseString(null, source)
}

/** Source text addressed by Tree-sitter's offsets, which are UTF-8 bytes, not chars. */
internal class SourceBytes(source: String) {
    private val bytes = source.toByteArray(Charsets.UTF_8)
    fun text(node: TSNode) = slice(node.startByte, node.endByte)
    fun slice(startByte: Int, endByte: Int) = String(bytes, startByte, endByte - startByte, Charsets.UTF_8)
}

/** A function extracted from source code, ready for embedding. */
data class FunctionChunk(
    val name: String,
    val receiver: String?,
    val parameters: String,
    val startLine: Int,
    val endLine: Int,
    val body: String,
    /** Enclosing class/object path, e.g. "Outer.Inner". Null for top-level functions. */
    val container: String? = null,
    /** Names of functions this one calls, unresolved (e.g. "embed", "search"). */
    val calls: List<String> = emptyList()
)

/** A class, interface or object declaration. */
data class ClassDecl(
    val name: String,
    /** "class", "interface" or "object". */
    val kind: String,
    /** Simple names of supertypes after the colon, e.g. ["Embedder"]. */
    val supertypes: List<String>,
    /** Enclosing class/object path. Null for top-level declarations. */
    val container: String?,
    val startLine: Int,
    val endLine: Int
) {
    val qualifiedName: String get() = container?.let { "$it.$name" } ?: name
}

private val CLASS_LIKE = setOf("class_declaration", "object_declaration")

/** Recursively find all function_declaration nodes in the AST. */
fun findFunctions(node: TSNode, source: String, results: MutableList<FunctionChunk>) =
    walkFunctions(node, SourceBytes(source), results, container = null)

private fun walkFunctions(
    node: TSNode,
    source: SourceBytes,
    results: MutableList<FunctionChunk>,
    container: String?
) {
    if (node.type == "function_declaration") {
        val name = findChildByType(node, "simple_identifier")
            ?.let { source.text(it) }
            ?: "<anonymous>"

        // Extension functions have a user_type child followed by "." before the name
        val hasDot = findChildByType(node, ".") != null
        val receiver = if (hasDot) {
            findChildByType(node, "user_type")
                ?.let { source.text(it) }
        } else null

        val params = findChildByType(node, "function_value_parameters")
            ?.let { source.text(it) }
            ?: "()"

        val calls = mutableSetOf<String>()
        findChildByType(node, "function_body")?.let { collectCalls(it, source, calls) }

        results.add(
            FunctionChunk(
                name = name,
                receiver = receiver,
                parameters = params,
                startLine = node.startPoint.row + 1,
                endLine = node.endPoint.row + 1,
                body = source.text(node),
                container = container,
                calls = calls.toList()
            )
        )
    }

    val childContainer = if (node.type in CLASS_LIKE) {
        findChildByType(node, "type_identifier")
            ?.let { source.text(it) }
            ?.let { name -> container?.let { "$it.$name" } ?: name }
            ?: container
    } else container

    for (i in 0 until node.childCount) {
        walkFunctions(node.getChild(i), source, results, childContainer)
    }
}

/**
 * Collect the names of called functions under [node]. Stops at nested
 * function and class declarations: their calls belong to them.
 */
private fun collectCalls(node: TSNode, source: SourceBytes, calls: MutableSet<String>) {
    if (node.type == "function_declaration" || node.type in CLASS_LIKE) return

    if (node.type == "call_expression" && node.childCount > 0) {
        calleeName(node.getChild(0), source)?.let { calls.add(it) }
    }
    for (i in 0 until node.childCount) {
        collectCalls(node.getChild(i), source, calls)
    }
}

/** `foo()` → "foo", `a.b.foo()` → "foo". Anything else (e.g. `f()()`) → null. */
private fun calleeName(callee: TSNode, source: SourceBytes): String? = when (callee.type) {
    "simple_identifier" -> source.text(callee)
    "navigation_expression" -> {
        val suffix = callee.getChild(callee.childCount - 1)
        findChildByType(suffix, "simple_identifier")
            ?.let { source.text(it) }
    }
    else -> null
}

/** Recursively find all class, interface and object declarations. */
fun findClassDecls(node: TSNode, source: String, results: MutableList<ClassDecl>) =
    walkClassDecls(node, SourceBytes(source), results, container = null)

private fun walkClassDecls(
    node: TSNode,
    source: SourceBytes,
    results: MutableList<ClassDecl>,
    container: String?
) {
    var childContainer = container
    if (node.type in CLASS_LIKE) {
        val nameNode = findChildByType(node, "type_identifier")
        if (nameNode != null) {
            val kind = when {
                node.type == "object_declaration" -> "object"
                findChildByType(node, "interface") != null -> "interface"
                else -> "class"
            }
            val decl = ClassDecl(
                name = source.text(nameNode),
                kind = kind,
                supertypes = supertypes(node, source),
                container = container,
                startLine = node.startPoint.row + 1,
                endLine = node.endPoint.row + 1
            )
            results.add(decl)
            childContainer = decl.qualifiedName
        }
    }

    for (i in 0 until node.childCount) {
        walkClassDecls(node.getChild(i), source, results, childContainer)
    }
}

/** Simple names of the types in a declaration's delegation_specifier list. */
private fun supertypes(classNode: TSNode, source: SourceBytes): List<String> {
    val names = mutableListOf<String>()
    for (i in 0 until classNode.childCount) {
        val spec = classNode.getChild(i)
        if (spec.type != "delegation_specifier") continue
        val userType = findDescendantByType(spec, "user_type") ?: continue
        // `a.b.Foo<T>` → last type_identifier is "Foo"
        var last: TSNode? = null
        for (j in 0 until userType.childCount) {
            val c = userType.getChild(j)
            if (c.type == "type_identifier") last = c
        }
        last?.let { names.add(source.text(it)) }
    }
    return names
}

/** Names of all classes, interfaces and objects. */
fun findClasses(node: TSNode, source: String, results: MutableList<String>) {
    val decls = mutableListOf<ClassDecl>()
    findClassDecls(node, source, decls)
    decls.mapTo(results) { it.name }
}

/** Find the first child of a specific type. */
fun findChildByType(node: TSNode, type: String): TSNode? {
    for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child.type == type) return child
    }
    return null
}

/** Depth-first search for the first descendant of a specific type. */
private fun findDescendantByType(node: TSNode, type: String): TSNode? {
    for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child.type == type) return child
        findDescendantByType(child, type)?.let { return it }
    }
    return null
}
