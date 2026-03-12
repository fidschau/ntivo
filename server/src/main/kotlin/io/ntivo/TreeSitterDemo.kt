package io.ntivo

import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TreeSitterKotlin
import java.io.File

/**
 * Tree-sitter demo that proves function-level code parsing works.
 * Parses a real Kotlin file and extracts individual functions —
 * exactly the chunks Ntivo will embed and index.
 *
 * Run with: ./gradlew runTreeSitterDemo
 * No API keys or Docker required.
 */
fun main() {
    val parser = TSParser()
    parser.setLanguage(TreeSitterKotlin())

    // Parse our own Routing.kt as a real-world example
    val sourceFile = File("server/src/main/kotlin/io/ntivo/Routing.kt")
    val sourceCode = sourceFile.readText()

    println("Parsing: ${sourceFile.path} (${sourceCode.length} chars)")
    println("=".repeat(60))

    val tree = parser.parseString(null, sourceCode)
    val root = tree.rootNode

    println("AST root: ${root.type} (${root.childCount} children)")
    println()

    // --- 1. Show the top-level AST structure ---
    println("Top-level AST nodes:")
    for (i in 0 until root.namedChildCount) {
        val child = root.getNamedChild(i)
        println("  ${child.type} [lines ${child.startPoint.row + 1}-${child.endPoint.row + 1}]")
    }
    println()

    // --- 2. Extract all function declarations (what Ntivo will chunk) ---
    val functions = mutableListOf<FunctionChunk>()
    findFunctions(root, sourceCode, functions)

    println("Extracted ${functions.size} function(s):")
    println("-".repeat(60))

    for (fn in functions) {
        println("Function: ${fn.name}")
        println("  Lines:      ${fn.startLine}-${fn.endLine}")
        println("  Parameters: ${fn.parameters}")
        println("  Receiver:   ${fn.receiver ?: "(none)"}")
        println("  Chunk size: ${fn.body.length} chars")
        println("  Body preview:")
        fn.body.lines().take(5).forEach { println("    $it") }
        if (fn.body.lines().size > 5) println("    ...")
        println()
    }

    // --- 3. Show how this feeds into the embedding pipeline ---
    println("=".repeat(60))
    println("These ${functions.size} function chunks are what gets embedded:")
    for (fn in functions) {
        println("  -> ${fn.name} (${fn.body.length} chars, lines ${fn.startLine}-${fn.endLine})")
    }
    println()

    // --- 4. Also parse a more complex inline example ---
    println("Parsing inline Kotlin with classes and extension functions...")
    println("-".repeat(60))

    val complexCode = """
        package io.ntivo.example

        import kotlinx.serialization.Serializable

        @Serializable
        data class CodeChunk(
            val id: String,
            val content: String,
            val fingerprint: String
        )

        fun CodeChunk.isStale(newFingerprint: String): Boolean {
            return fingerprint != newFingerprint
        }

        suspend fun indexRepository(orgId: String, repoUrl: String): Int {
            val chunks = parseAllFiles(repoUrl)
            val fresh = chunks.filter { !it.isStale(computeHash(it.content)) }
            return embedAndStore(orgId, fresh)
        }

        private fun computeHash(content: String): String {
            return java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    """.trimIndent()

    val tree2 = parser.parseString(null, complexCode)
    val functions2 = mutableListOf<FunctionChunk>()
    findFunctions(tree2.rootNode, complexCode, functions2)

    val classes = mutableListOf<String>()
    findClasses(tree2.rootNode, complexCode, classes)

    println("Found ${classes.size} class(es): ${classes.joinToString()}")
    println("Found ${functions2.size} function(s):")
    for (fn in functions2) {
        println("  -> ${fn.name}${fn.receiver?.let { " (extension on $it)" } ?: ""}" +
                " [${fn.startLine}-${fn.endLine}] (${fn.body.length} chars)")
    }

    // TSParser and TSTree use GC-based cleanup (no close() needed)
    println("\nTree-sitter demo complete!")
}

/** A function extracted from source code, ready for embedding. */
data class FunctionChunk(
    val name: String,
    val receiver: String?,
    val parameters: String,
    val startLine: Int,
    val endLine: Int,
    val body: String
)

/** Recursively find all function_declaration nodes in the AST. */
fun findFunctions(node: TSNode, source: String, results: MutableList<FunctionChunk>) {
    if (node.type == "function_declaration") {
        val name = findChildByType(node, "simple_identifier")
            ?.let { source.substring(it.startByte, it.endByte) }
            ?: "<anonymous>"

        // Extension functions have a user_type child followed by "." before the name
        val hasDot = findChildByType(node, ".") != null
        val receiver = if (hasDot) {
            findChildByType(node, "user_type")
                ?.let { source.substring(it.startByte, it.endByte) }
        } else null

        val params = findChildByType(node, "function_value_parameters")
            ?.let { source.substring(it.startByte, it.endByte) }
            ?: "()"

        results.add(
            FunctionChunk(
                name = name,
                receiver = receiver,
                parameters = params,
                startLine = node.startPoint.row + 1,
                endLine = node.endPoint.row + 1,
                body = source.substring(node.startByte, node.endByte)
            )
        )
    }

    for (i in 0 until node.childCount) {
        findFunctions(node.getChild(i), source, results)
    }
}

/** Recursively find all class/data class declarations. */
fun findClasses(node: TSNode, source: String, results: MutableList<String>) {
    if (node.type == "class_declaration") {
        val nameNode = findChildByType(node, "type_identifier")
        if (nameNode != null) {
            results.add(source.substring(nameNode.startByte, nameNode.endByte))
        }
    }

    for (i in 0 until node.childCount) {
        findClasses(node.getChild(i), source, results)
    }
}

/** Find the first child of a specific type. */
fun findChildByType(node: TSNode, type: String): TSNode? {
    for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child.type == type) return child
    }
    return null
}
