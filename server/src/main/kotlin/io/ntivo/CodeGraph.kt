package io.ntivo

import io.ntivo.shared.GraphIndexResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Driver
import org.neo4j.driver.EagerResult
import org.neo4j.driver.GraphDatabase
import java.io.File

/**
 * Builds a code knowledge graph in Neo4j from Kotlin source.
 *
 * Schema (every node also carries the `:Code` label and a unique `id`):
 * ```
 * (:File)-[:DECLARES]->(:Class|:Function)
 * (:Class)-[:DECLARES]->(:Class|:Function)
 * (:Function)-[:CALLS {method, confidence}]->(:Function)
 * (:Class)-[:INHERITS {method, confidence}]->(:Class)
 * ```
 * CALLS and INHERITS are resolved by name only (method = "NAME_MATCH"), so when
 * a name matches N declarations each edge gets confidence 1/N.
 *
 * Indexing is a full rebuild: all `:Code` nodes are deleted first.
 */
class CodeGraph(
    uri: String = System.getenv("NTIVO_NEO4J_URI") ?: "bolt://localhost:7687",
    password: String = System.getenv("NTIVO_NEO4J_PASSWORD") ?: "ntivo_dev_password"
) {
    // Connects lazily, so the server starts even when Neo4j is down
    private val driver: Driver = GraphDatabase.driver(uri, AuthTokens.basic("neo4j", password))

    suspend fun index(root: File): GraphIndexResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()

        val files = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        val fileRows = mutableListOf<Map<String, Any?>>()
        val classRows = mutableListOf<Map<String, Any?>>()
        val functionRows = mutableListOf<Map<String, Any?>>()
        val declares = mutableListOf<Map<String, String>>()

        for (file in files) {
            val path = file.relativeTo(root).path
            val source = file.readText()
            val tree = parseKotlin(source)

            val classes = mutableListOf<ClassDecl>()
            findClassDecls(tree.rootNode, source, classes)
            val functions = mutableListOf<FunctionChunk>()
            findFunctions(tree.rootNode, source, functions)

            val fileId = "FILE:$path"
            fun parentId(container: String?) = container?.let { "CLASS:$path#$it" } ?: fileId

            fileRows += mapOf("id" to fileId, "path" to path, "name" to file.name)

            for (c in classes) {
                val id = "CLASS:$path#${c.qualifiedName}"
                classRows += mapOf(
                    "id" to id, "name" to c.name, "qualifiedName" to c.qualifiedName,
                    "kind" to c.kind, "supertypes" to c.supertypes, "path" to path,
                    "startLine" to c.startLine, "endLine" to c.endLine
                )
                declares += mapOf("from" to parentId(c.container), "to" to id)
            }

            for (fn in functions) {
                val qualifiedName = listOfNotNull(fn.container, fn.receiver, fn.name).joinToString(".")
                val id = "FUNCTION:$path#$qualifiedName:${fn.startLine}"
                functionRows += mapOf(
                    "id" to id, "name" to fn.name, "qualifiedName" to qualifiedName,
                    "receiver" to fn.receiver, "parameters" to fn.parameters, "calls" to fn.calls,
                    "path" to path, "startLine" to fn.startLine, "endLine" to fn.endLine
                )
                declares += mapOf("from" to parentId(fn.container), "to" to id)
            }
        }

        ensureSchema()
        run("MATCH (n:Code) DETACH DELETE n")
        run("UNWIND \$rows AS r CREATE (n:Code:File) SET n = r", mapOf("rows" to fileRows))
        run("UNWIND \$rows AS r CREATE (n:Code:Class) SET n = r", mapOf("rows" to classRows))
        run("UNWIND \$rows AS r CREATE (n:Code:Function) SET n = r", mapOf("rows" to functionRows))
        run(
            """
            UNWIND ${'$'}rows AS r
            MATCH (parent:Code {id: r.from}), (child:Code {id: r.to})
            CREATE (parent)-[:DECLARES]->(child)
            """,
            mapOf("rows" to declares)
        )

        val calls = resolveByName(
            """
            MATCH (caller:Function) UNWIND caller.calls AS name
            MATCH (callee:Function {name: name})
            WITH caller, name, collect(callee) AS candidates
            UNWIND candidates AS callee
            CREATE (caller)-[:CALLS {method: 'NAME_MATCH', confidence: 1.0 / size(candidates)}]->(callee)
            RETURN count(*) AS edges, sum(CASE WHEN size(candidates) > 1 THEN 1 ELSE 0 END) AS ambiguous
            """
        )
        val inherits = resolveByName(
            """
            MATCH (sub:Class) UNWIND sub.supertypes AS name
            MATCH (sup:Class {name: name})
            WITH sub, name, collect(sup) AS candidates
            UNWIND candidates AS sup
            CREATE (sub)-[:INHERITS {method: 'NAME_MATCH', confidence: 1.0 / size(candidates)}]->(sup)
            RETURN count(*) AS edges, 0 AS ambiguous
            """
        )

        GraphIndexResponse(
            root = root.path,
            files = fileRows.size,
            classes = classRows.size,
            functions = functionRows.size,
            callEdges = calls.first,
            ambiguousCallEdges = calls.second,
            inheritsEdges = inherits.first,
            durationMs = System.currentTimeMillis() - start
        )
    }

    private fun ensureSchema() {
        run("CREATE CONSTRAINT code_id IF NOT EXISTS FOR (n:Code) REQUIRE n.id IS UNIQUE")
        run("CREATE INDEX function_name IF NOT EXISTS FOR (n:Function) ON (n.name)")
        run("CREATE INDEX class_name IF NOT EXISTS FOR (n:Class) ON (n.name)")
    }

    /** Runs a resolution query returning `edges` and `ambiguous` counts. */
    private fun resolveByName(cypher: String): Pair<Int, Int> {
        val record = run(cypher).records().single()
        return record["edges"].asInt() to record["ambiguous"].asInt()
    }

    private fun run(cypher: String, params: Map<String, Any?> = emptyMap()): EagerResult =
        driver.executableQuery(cypher.trimIndent()).withParameters(params).execute()

    fun close() = driver.close()
}
