package io.ntivo.agent

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import io.ntivo.CodeGraph
import io.ntivo.GraphEdge
import io.ntivo.GraphNode
import io.ntivo.model.TraceHop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Graph queries over the ready sources ([sourceIds]): who calls a function, what it
 * calls, what a file declares. Read-only; results are capped by [CodeGraph.MAX_RESULTS].
 *
 * Every traversal counts against the hop budget in [trace], every function shown to the
 * model becomes citable, and every edge is recorded as a hop for the trace panel.
 */
@LLMDescription("Follow connections in the connected repositories: callers, callees and declarations")
class GraphTools(
    private val graph: CodeGraph,
    private val sourceIds: List<String>,
    private val trace: TraceRecorder
) : ToolSet {

    private val reachable by lazy { graph.isReachable() }

    @Tool
    @LLMDescription(
        "List the functions that call a function, across the connected repositories. " +
            "Use it to follow up on a function a search already found, e.g. to see where a limit is enforced. " +
            "Do not use for general questions or when the previous search already answered the question."
    )
    suspend fun callersOf(
        @LLMDescription("Simple function name without parentheses or class, e.g. 'friendLimit'")
        functionName: String
    ): String = trace.guarded("callersOf", functionName) {
        safely { traverse(functionName) { graph.callersOf(functionName, sourceIds) } }
    }

    @Tool
    @LLMDescription(
        "List the functions a function calls, across the connected repositories. " +
            "Use it to follow up on a function a search already found, e.g. to see where a value comes from. " +
            "Do not use for general questions or when the previous search already answered the question."
    )
    suspend fun calleesOf(
        @LLMDescription("Simple function name without parentheses or class, e.g. 'friendLimit'")
        functionName: String
    ): String = trace.guarded("calleesOf", functionName) {
        safely { traverse(functionName) { graph.calleesOf(functionName, sourceIds) } }
    }

    @Tool
    @LLMDescription(
        "List the classes and functions declared in a file, by the end of its path. " +
            "Use it when a search result points at a file and you need its other declarations. " +
            "Do not use for general questions or when the previous search already answered the question."
    )
    suspend fun declarationsIn(
        @LLMDescription("End of a file path, e.g. 'InviteService.swift' or 'invite/InviteRepository.kt'")
        filePath: String
    ): String = trace.guarded("declarationsIn", filePath) { safely { declarations(filePath) } }

    private suspend fun declarations(filePath: String): Pair<String, String> {
        if (sourceIds.isEmpty()) return NO_SOURCES to "no ready sources"
        if (!reachable) return GRAPH_DOWN to "graph unavailable"
        val nodes = withContext(Dispatchers.IO) { graph.declarationsIn(filePath, sourceIds) }
        if (nodes.isEmpty()) {
            return "No declarations found in files ending with '$filePath'." to "0 declarations"
        }
        nodes.mapNotNull { it.pieceId }.forEach(trace::surfaced)
        val text = buildString {
            appendLine("${nodes.size} declaration${plural(nodes.size)} in files ending with '$filePath':")
            nodes.forEach { appendLine(nodeLine(it)) }
        }.trimEnd()
        return text to "${nodes.size} declaration${plural(nodes.size)}"
    }

    /** A failing query becomes a plain message to the model, recorded as failed in the trace. */
    private inline fun safely(block: () -> Pair<String, String>): Pair<String, String> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        GRAPH_DOWN to "failed: ${safeMessage(e)}"
    }

    private suspend fun traverse(functionName: String, query: () -> List<GraphEdge>): Pair<String, String> {
        if (sourceIds.isEmpty()) return NO_SOURCES to "no ready sources"
        if (!reachable) return GRAPH_DOWN to "graph unavailable"
        if (!trace.allowHop()) return HOP_LIMIT_MESSAGE to "hop limit reached"

        val edges = withContext(Dispatchers.IO) { query() }
        if (edges.isEmpty()) {
            return "No connections found for '$functionName' in the ready sources." to "0 connections"
        }
        for (edge in edges) {
            edge.from.pieceId?.let(trace::surfaced)
            edge.to.pieceId?.let(trace::surfaced)
            trace.hop(TraceHop(edge.from.qualifiedName, edge.relation.lowercase(), edge.to.qualifiedName, edge.confidence))
        }
        val text = buildString {
            appendLine("${edges.size} connection${plural(edges.size)}:")
            for (edge in edges) {
                appendLine(
                    "${nodeLine(edge.from)} ${edge.relation.lowercase()} ${nodeLine(edge.to)} " +
                        "(confidence ${"%.2f".format(edge.confidence)})"
                )
            }
        }.trimEnd()
        return text to "${edges.size} connection${plural(edges.size)}"
    }

    private fun nodeLine(n: GraphNode): String {
        val id = n.pieceId?.let { "[$it] " }.orEmpty()
        return "$id${n.label.lowercase()} ${n.qualifiedName} (${n.sourceName} ${n.path}:${n.startLine}-${n.endLine})"
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private companion object {
        const val NO_SOURCES = "No sources are ready to query."
        const val GRAPH_DOWN = "Connections are not available right now. Answer from search results."
    }
}
