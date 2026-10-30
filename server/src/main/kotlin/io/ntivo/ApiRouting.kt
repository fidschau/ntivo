package io.ntivo

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ntivo.shared.*
import java.io.File

private const val MISSING_KEY = "NTIVO_GEMINI_API_KEY not set. Export it and restart the server."

// --- API routing ---

fun Application.configureApiRouting(ctx: AppContext) {
    // Shared, long-lived clients from the app context. Gemini-backed ones are null if the API key is missing.
    val executor = ctx.executor
    val embedder = ctx.embedder
    val vectorStore = ctx.vectorStore
    val codeGraph = ctx.codeGraph

    routing {
        route("/api") {

            // POST /api/agent-chat — Codebase agent with search_codebase tool
            post("/agent-chat") {
                val request = call.receive<AgentChatRequest>()
                if (request.prompt.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("'prompt' is required and cannot be blank."))
                }
                if (executor == null || embedder == null) {
                    return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(MISSING_KEY))
                }

                // Fresh tools + agent per request: the call log belongs to this run only
                val tools = CodeSearchTools(embedder, vectorStore, request.collection)
                val agent = createCodebaseAgent(executor, tools)
                try {
                    val result = agent.run(request.prompt)
                    call.respond(AgentChatResponse(
                        response = result,
                        toolCalls = tools.callLog.map { tc ->
                            AgentToolCall(tool = tc.tool, args = tc.args, result = tc.resultSummary)
                        }
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway,
                        ErrorResponse("Agent error: ${e.message}"))
                }
            }

            // POST /api/search — Query Qdrant with a natural language search
            post("/search") {
                val request = call.receive<SearchRequest>()
                if (request.query.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("'query' is required and cannot be blank."))
                }
                val emb = embedder
                    ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(MISSING_KEY))

                try {
                    if (!vectorStore.collectionExists(request.collection)) {
                        return@post call.respond(HttpStatusCode.NotFound,
                            ErrorResponse("Collection '${request.collection}' not found. " +
                                "Index some code on the RAG Pipeline tab first."))
                    }

                    val queryVector = emb.embed(request.query, EmbeddingTaskType.CODE_RETRIEVAL_QUERY)
                    val results = vectorStore.search(
                        request.collection, queryVector.values, request.limit.coerceIn(1, 20)
                    )

                    call.respond(SearchResponse(
                        results = results.map { r ->
                            SearchResultItem(
                                score = r.score,
                                payload = r.payloadMap.mapValues { it.value.stringValue }
                            )
                        }
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError,
                        ErrorResponse("Search error: ${e.message}. Is Qdrant running? docker compose up -d"))
                }
            }

            // POST /api/rag-pipeline — Full pipeline: parse → chunk → embed → store
            post("/rag-pipeline") {
                val request = call.receive<RagPipelineRequest>()
                if (request.code.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("'code' is required and cannot be blank."))
                }
                val emb = embedder
                    ?: return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(MISSING_KEY))

                val steps = mutableListOf<PipelineStep>()
                val pipelineStart = System.currentTimeMillis()
                fun elapsed() = "${System.currentTimeMillis() - pipelineStart}ms"

                // Step 1: Parse
                val parseStart = System.currentTimeMillis()
                val functions = mutableListOf<FunctionChunk>()
                val classes = mutableListOf<String>()
                try {
                    val root = parseKotlin(request.code).rootNode
                    findFunctions(root, request.code, functions)
                    findClasses(root, request.code, classes)
                    steps.add(PipelineStep(
                        stage = "parse",
                        message = "Tree-sitter found ${classes.size} class(es), ${functions.size} function(s)",
                        success = true,
                        durationMs = System.currentTimeMillis() - parseStart
                    ))
                } catch (e: Exception) {
                    steps.add(PipelineStep("parse", "Parse failed: ${e.message}", false,
                        System.currentTimeMillis() - parseStart))
                    return@post call.respond(RagPipelineResponse(steps, 0, elapsed()))
                }

                if (functions.isEmpty()) {
                    steps.add(PipelineStep("chunk", "No functions found to index", false, 0))
                    return@post call.respond(RagPipelineResponse(steps, 0, elapsed()))
                }

                // Step 2: Chunk
                steps.add(PipelineStep(
                    stage = "chunk",
                    message = "Created ${functions.size} chunks (function-level)",
                    success = true,
                    durationMs = 0
                ))

                // Step 3 & 4: Embed + Store, skipping functions whose body hasn't changed
                try {
                    vectorStore.ensureCollection(request.collection)
                    val existing = vectorStore.fingerprints(request.collection, functions.map { it.pointId() })

                    var stored = 0
                    var skipped = 0
                    for (fn in functions) {
                        val fingerprint = fn.fingerprint()
                        if (existing[fn.pointId()] == fingerprint) {
                            skipped++
                            steps.add(PipelineStep("skip",
                                "${fn.name}: unchanged (sha256 ${fingerprint.take(8)}), kept existing vector",
                                true, 0))
                            continue
                        }

                        val embedStart = System.currentTimeMillis()
                        val vector = emb.embed(fn.body, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
                        val embedMs = System.currentTimeMillis() - embedStart
                        steps.add(PipelineStep(
                            stage = "embed",
                            message = "${fn.name}: ${vector.dimension}-dim vector in ${embedMs}ms",
                            success = true,
                            durationMs = embedMs
                        ))

                        vectorStore.upsert(request.collection, fn.pointId(), vector.values, fn.payload())
                        stored++
                    }

                    steps.add(PipelineStep(
                        stage = "store",
                        message = "$stored stored, $skipped unchanged in ${request.collection} (cosine)",
                        success = true,
                        durationMs = 0
                    ))

                    call.respond(RagPipelineResponse(
                        steps = steps,
                        chunksIndexed = stored,
                        totalTime = elapsed(),
                        chunksSkipped = skipped
                    ))
                } catch (e: Exception) {
                    steps.add(PipelineStep("store", "Store error: ${e.message}", false, 0))
                    call.respond(RagPipelineResponse(steps, 0, elapsed()))
                }
            }

            // POST /api/parse — Parse Kotlin code with tree-sitter
            post("/parse") {
                val request = call.receive<ParseRequest>()
                if (request.code.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("'code' is required and cannot be blank."))
                }
                try {
                    val root = parseKotlin(request.code).rootNode

                    val functions = mutableListOf<FunctionChunk>()
                    findFunctions(root, request.code, functions)

                    val classes = mutableListOf<String>()
                    findClasses(root, request.code, classes)

                    call.respond(ParseResponse(
                        functions = functions.map { fn ->
                            ParsedFunction(
                                name = fn.name,
                                receiver = fn.receiver,
                                parameters = fn.parameters,
                                startLine = fn.startLine,
                                endLine = fn.endLine,
                                bodyLength = fn.body.length
                            )
                        },
                        classes = classes,
                        nodeCount = root.namedChildCount
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError,
                        ErrorResponse("Parse error: ${e.message}"))
                }
            }

            // POST /api/graph/index — Parse every .kt file under a directory into Neo4j
            post("/graph/index") {
                val request = call.receive<GraphIndexRequest>()
                val root = File(request.path).absoluteFile.normalize()
                if (!root.isDirectory) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("'${request.path}' is not a directory (resolved to $root)."))
                }
                try {
                    call.respond(codeGraph.index(root))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError,
                        ErrorResponse("Graph index error: ${e.message}. Is Neo4j running? docker compose up -d"))
                }
            }
        }
    }
}
