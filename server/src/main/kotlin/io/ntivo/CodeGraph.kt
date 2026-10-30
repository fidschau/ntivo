package io.ntivo

import io.ntivo.parse.KotlinParser
import io.ntivo.parse.Language
import io.ntivo.shared.GraphIndexResponse
import io.ntivo.sources.GraphIndexer
import io.ntivo.sources.GraphStats
import io.ntivo.sources.ParsedSourceFile
import io.ntivo.sources.nodeId
import io.ntivo.sources.pieceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.neo4j.driver.AuthTokens
import org.neo4j.driver.Config
import org.neo4j.driver.Driver
import org.neo4j.driver.EagerResult
import org.neo4j.driver.GraphDatabase
import org.neo4j.driver.types.Node
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/** A declaration in the graph, as returned to the agent. [pieceId] is set on functions only. */
data class GraphNode(
    val id: String,
    val label: String,
    val name: String,
    val qualifiedName: String,
    val sourceName: String,
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val pieceId: String?
)

data class GraphEdge(val from: GraphNode, val relation: String, val to: GraphNode, val confidence: Float)

/**
 * Code graph in Neo4j, one subgraph per source.
 *
 * Schema (every node also carries the `:Code` label, a unique `id`, and
 * `sourceId`, `sourceName`, `language`):
 * ```
 * (:File)-[:DECLARES]->(:Class|:Function)
 * (:Class)-[:DECLARES]->(:Class|:Function)
 * (:Function)-[:CALLS {method, confidence}]->(:Function)
 * (:Class)-[:INHERITS {method, confidence}]->(:Class)
 * ```
 * Function nodes also carry `pieceId`, the id of the matching Qdrant point.
 *
 * CALLS and INHERITS are resolved by name. Candidates in the same source win
 * (method `NAME_MATCH`); only when there are none are other sources tried
 * (method `NAME_MATCH_CROSS_SOURCE`). When a name matches N candidates each
 * edge gets confidence 1/N.
 *
 * [rebuild] replaces one source's nodes and leaves other sources alone. Edges
 * from another source into this one are removed with the old nodes and come
 * back when that other source is rebuilt.
 */
class CodeGraph(
    uri: String = System.getenv("NTIVO_NEO4J_URI") ?: "bolt://localhost:7687",
    password: String = System.getenv("NTIVO_NEO4J_PASSWORD") ?: "ntivo_dev_password"
) : GraphIndexer {
    private val boltUri = URI(uri)

    // Connects lazily, so the server starts even when Neo4j is down
    private val driver: Driver = GraphDatabase.driver(
        uri,
        AuthTokens.basic("neo4j", password),
        Config.builder().withConnectionTimeout(5, TimeUnit.SECONDS).build()
    )

    @Volatile
    private var schemaReady = false

    /**
     * True when Neo4j answers. Probes the port first: the driver retries a refused
     * connection for tens of seconds before giving up.
     */
    fun isReachable(): Boolean {
        val portOpen = runCatching {
            Socket().use { it.connect(InetSocketAddress(boltUri.host, boltUri.port.takeIf { p -> p > 0 } ?: 7687), 2_000) }
        }.isSuccess
        return portOpen && runCatching { driver.verifyConnectivity() }.isSuccess
    }

    /**
     * Parses every `.kt` file under [root] into the graph as source `local`.
     * Used by the "Under the hood" page.
     */
    suspend fun index(root: File): GraphIndexResponse = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val files = root.walkTopDown()
            .onEnter { it.name != "build" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .map { ParsedSourceFile(it.relativeTo(root).path, Language.KOTLIN, KotlinParser.parse(it.readText())) }
            .toList()

        val result = write(LOCAL_SOURCE_ID, LOCAL_SOURCE_ID, files) { _, _ -> }
        GraphIndexResponse(
            root = root.path,
            files = files.size,
            classes = result.stats.classes,
            functions = result.stats.functions,
            callEdges = result.stats.callEdges,
            ambiguousCallEdges = result.ambiguousCallEdges,
            inheritsEdges = result.stats.inheritsEdges,
            durationMs = System.currentTimeMillis() - start
        )
    }

    override suspend fun rebuild(
        sourceId: String,
        sourceName: String,
        files: List<ParsedSourceFile>,
        onProgress: (Int, Int) -> Unit
    ): GraphStats = withContext(Dispatchers.IO) {
        write(sourceId, sourceName, files, onProgress).stats
    }

    /** Removes every node (and its edges) of [sourceId]. */
    fun deleteSource(sourceId: String) {
        run("MATCH (n:Code {sourceId: \$sourceId}) DETACH DELETE n", mapOf("sourceId" to sourceId))
    }

    /** Number of nodes (files, classes, functions) stored for [sourceId]. */
    fun countNodes(sourceId: String): Int =
        run("MATCH (n:Code {sourceId: \$sourceId}) RETURN count(n) AS c", mapOf("sourceId" to sourceId))
            .records().single()["c"].asInt()

    // --- Read queries for the agent. Both ends of an edge must be in [sourceIds]. ---

    /** Functions named [name] and who calls them. */
    fun callersOf(name: String, sourceIds: List<String>, limit: Int = MAX_RESULTS): List<GraphEdge> =
        edges(
            """
            MATCH (from:Code:Function)-[r:CALLS]->(to:Code:Function {name: ${'$'}name})
            WHERE from.sourceId IN ${'$'}sourceIds AND to.sourceId IN ${'$'}sourceIds
            RETURN from, type(r) AS relation, r.confidence AS confidence, to
            ORDER BY to.sourceName, to.path, from.sourceName, from.path, from.startLine
            LIMIT ${'$'}limit
            """,
            name, sourceIds, limit
        )

    /** Functions named [name] and what they call. */
    fun calleesOf(name: String, sourceIds: List<String>, limit: Int = MAX_RESULTS): List<GraphEdge> =
        edges(
            """
            MATCH (from:Code:Function {name: ${'$'}name})-[r:CALLS]->(to:Code:Function)
            WHERE from.sourceId IN ${'$'}sourceIds AND to.sourceId IN ${'$'}sourceIds
            RETURN from, type(r) AS relation, r.confidence AS confidence, to
            ORDER BY from.sourceName, from.path, to.sourceName, to.path, to.startLine
            LIMIT ${'$'}limit
            """,
            name, sourceIds, limit
        )

    /** Classes and functions declared in files whose path ends with [pathSuffix]. */
    fun declarationsIn(pathSuffix: String, sourceIds: List<String>, limit: Int = MAX_RESULTS): List<GraphNode> {
        if (sourceIds.isEmpty() || pathSuffix.isBlank()) return emptyList()
        return run(
            """
            MATCH (n:Code)
            WHERE n.sourceId IN ${'$'}sourceIds AND (n:Class OR n:Function) AND n.path ENDS WITH ${'$'}suffix
            RETURN n
            ORDER BY n.sourceName, n.path, n.startLine
            LIMIT ${'$'}limit
            """,
            mapOf("sourceIds" to sourceIds, "suffix" to pathSuffix, "limit" to limit.coerceIn(1, MAX_RESULTS))
        ).records().map { toGraphNode(it["n"].asNode()) }
    }

    private fun edges(cypher: String, name: String, sourceIds: List<String>, limit: Int): List<GraphEdge> {
        if (sourceIds.isEmpty()) return emptyList()
        return run(
            cypher,
            mapOf("name" to name, "sourceIds" to sourceIds, "limit" to limit.coerceIn(1, MAX_RESULTS))
        ).records().map { r ->
            GraphEdge(
                from = toGraphNode(r["from"].asNode()),
                relation = r["relation"].asString(),
                to = toGraphNode(r["to"].asNode()),
                confidence = r["confidence"].asDouble(1.0).toFloat()
            )
        }
    }

    private fun toGraphNode(n: Node): GraphNode {
        fun str(key: String) = n[key].takeUnless { it.isNull }?.asString()
        fun int(key: String) = n[key].takeUnless { it.isNull }?.asInt() ?: 0
        val label = n.labels().firstOrNull { it != "Code" } ?: "Code"
        val path = str("path").orEmpty()
        return GraphNode(
            id = str("id").orEmpty(),
            label = label,
            name = str("name").orEmpty(),
            qualifiedName = str("qualifiedName") ?: path,
            sourceName = str("sourceName").orEmpty(),
            path = path,
            startLine = int("startLine"),
            endLine = int("endLine"),
            pieceId = str("pieceId")
        )
    }

    // --- Writing ---

    private class WriteResult(val stats: GraphStats, val ambiguousCallEdges: Int)

    private fun write(
        sourceId: String,
        sourceName: String,
        files: List<ParsedSourceFile>,
        onProgress: (Int, Int) -> Unit
    ): WriteResult {
        ensureSchema()
        deleteSource(sourceId)

        var classCount = 0
        var functionCount = 0
        var done = 0
        onProgress(0, files.size)

        // Write in file batches; a file's DECLARES edges only point inside that file
        for (batch in files.chunked(FILE_BATCH)) {
            val rows = Rows()
            for (file in batch) addFile(rows, sourceId, sourceName, file)
            run("UNWIND \$rows AS r CREATE (n:Code:File) SET n = r", mapOf("rows" to rows.files))
            run("UNWIND \$rows AS r CREATE (n:Code:Class) SET n = r", mapOf("rows" to rows.classes))
            run("UNWIND \$rows AS r CREATE (n:Code:Function) SET n = r", mapOf("rows" to rows.functions))
            run(
                """
                UNWIND ${'$'}rows AS r
                MATCH (parent:Code {id: r.from}), (child:Code {id: r.to})
                CREATE (parent)-[:DECLARES]->(child)
                """,
                mapOf("rows" to rows.declares)
            )
            classCount += rows.classes.size
            functionCount += rows.functions.size
            done += batch.size
            onProgress(done, files.size)
        }

        val calls = resolveByName(
            """
            MATCH (caller:Code:Function {sourceId: ${'$'}sourceId}) UNWIND caller.calls AS name
            MATCH (callee:Code:Function {name: name})
            WHERE callee.sourceId IS NOT NULL
            WITH caller, name, collect(callee) AS all
            WITH caller, all, [c IN all WHERE c.sourceId = ${'$'}sourceId] AS local
            WITH caller, CASE WHEN size(local) > 0 THEN local ELSE all END AS candidates,
                 CASE WHEN size(local) > 0 THEN 'NAME_MATCH' ELSE 'NAME_MATCH_CROSS_SOURCE' END AS method
            UNWIND candidates AS callee
            CREATE (caller)-[:CALLS {method: method, confidence: 1.0 / size(candidates)}]->(callee)
            RETURN count(*) AS edges, sum(CASE WHEN size(candidates) > 1 THEN 1 ELSE 0 END) AS ambiguous
            """,
            sourceId
        )
        val inherits = resolveByName(
            """
            MATCH (sub:Code:Class {sourceId: ${'$'}sourceId}) UNWIND sub.supertypes AS name
            MATCH (sup:Code:Class {name: name})
            WHERE sup.sourceId IS NOT NULL
            WITH sub, name, collect(sup) AS all
            WITH sub, all, [c IN all WHERE c.sourceId = ${'$'}sourceId] AS local
            WITH sub, CASE WHEN size(local) > 0 THEN local ELSE all END AS candidates,
                 CASE WHEN size(local) > 0 THEN 'NAME_MATCH' ELSE 'NAME_MATCH_CROSS_SOURCE' END AS method
            UNWIND candidates AS sup
            CREATE (sub)-[:INHERITS {method: method, confidence: 1.0 / size(candidates)}]->(sup)
            RETURN count(*) AS edges, sum(0) AS ambiguous
            """,
            sourceId
        )

        return WriteResult(
            GraphStats(
                classes = classCount,
                functions = functionCount,
                callEdges = calls.first,
                inheritsEdges = inherits.first
            ),
            ambiguousCallEdges = calls.second
        )
    }

    private class Rows {
        val files = mutableListOf<Map<String, Any?>>()
        val classes = mutableListOf<Map<String, Any?>>()
        val functions = mutableListOf<Map<String, Any?>>()
        val declares = mutableListOf<Map<String, String>>()
    }

    private fun addFile(rows: Rows, sourceId: String, sourceName: String, file: ParsedSourceFile) {
        val path = file.path
        val language = file.language.displayName
        val fileId = nodeId(sourceId, "FILE", path, "")
        fun parentId(container: String?) = container?.let { nodeId(sourceId, "CLASS", path, it) } ?: fileId
        val common = mapOf("sourceId" to sourceId, "sourceName" to sourceName, "language" to language, "path" to path)

        rows.files += common + mapOf("id" to fileId, "name" to path.substringAfterLast('/'))

        // Ids must be unique: a file can declare the same name twice (e.g. a Swift extension)
        val classIds = mutableSetOf<String>()
        for (c in file.parsed.classes) {
            val id = nodeId(sourceId, "CLASS", path, c.qualifiedName)
            if (!classIds.add(id)) continue
            rows.classes += common + mapOf(
                "id" to id, "name" to c.name, "qualifiedName" to c.qualifiedName,
                "kind" to c.kind, "supertypes" to c.supertypes,
                "startLine" to c.startLine, "endLine" to c.endLine
            )
            rows.declares += mapOf("from" to parentId(c.container), "to" to id)
        }

        val functionIds = mutableSetOf<String>()
        // Same ids as toPieces, so repeated signatures in one file point at distinct pieces
        val pieceIds = pieceIds(sourceId, path, file.parsed.functions)
        for ((i, fn) in file.parsed.functions.withIndex()) {
            val qualifiedName = listOfNotNull(fn.container, fn.receiver, fn.name).joinToString(".")
            val id = nodeId(sourceId, "FUNCTION", path, "$qualifiedName:${fn.startLine}")
            if (!functionIds.add(id)) continue
            rows.functions += common + mapOf(
                "id" to id, "name" to fn.name, "qualifiedName" to qualifiedName,
                "receiver" to fn.receiver, "parameters" to fn.parameters, "calls" to fn.calls,
                "startLine" to fn.startLine, "endLine" to fn.endLine,
                "pieceId" to pieceIds[i]
            )
            // A container the parser saw but did not emit as a class falls back to the file
            val parent = parentId(fn.container).takeIf { it == fileId || it in classIds } ?: fileId
            rows.declares += mapOf("from" to parent, "to" to id)
        }
    }

    private fun ensureSchema() {
        if (schemaReady) return
        run("CREATE CONSTRAINT code_id IF NOT EXISTS FOR (n:Code) REQUIRE n.id IS UNIQUE")
        run("CREATE INDEX code_source IF NOT EXISTS FOR (n:Code) ON (n.sourceId)")
        run("CREATE INDEX function_name IF NOT EXISTS FOR (n:Function) ON (n.name)")
        run("CREATE INDEX class_name IF NOT EXISTS FOR (n:Class) ON (n.name)")
        // Nodes from the earlier single-graph schema have no sourceId and would never be cleaned up
        run("MATCH (n:Code) WHERE n.sourceId IS NULL DETACH DELETE n")
        schemaReady = true
    }

    /**
     * Runs a resolution query returning `edges` and `ambiguous` counts. Both must be
     * aggregates: a constant next to an aggregate is a grouping key, and then empty
     * input yields no row at all.
     */
    private fun resolveByName(cypher: String, sourceId: String): Pair<Int, Int> {
        val record = run(cypher, mapOf("sourceId" to sourceId)).records().singleOrNull() ?: return 0 to 0
        return record["edges"].asInt() to record["ambiguous"].asInt()
    }

    private fun run(cypher: String, params: Map<String, Any?> = emptyMap()): EagerResult =
        driver.executableQuery(cypher.trimIndent()).withParameters(params).execute()

    fun close() = driver.close()

    companion object {
        /** Source id used by [index] for the "Under the hood" page. */
        const val LOCAL_SOURCE_ID = "local"

        /** Guardrail: graph queries return at most this many rows. */
        const val MAX_RESULTS = 50

        private const val FILE_BATCH = 200
    }
}
