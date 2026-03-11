package io.ntivo

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.google.GoogleModels
import ai.koog.prompt.executor.llms.all.simpleGoogleAIExecutor
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.WithPayloadSelectorFactory
import io.qdrant.client.grpc.Points.SearchPoints
import kotlinx.coroutines.guava.await
import kotlinx.serialization.Serializable
import org.treesitter.TSParser
import org.treesitter.TreeSitterKotlin

// --- Request/response models ---

@Serializable
data class ChatRequest(val prompt: String)

@Serializable
data class ChatResponse(val response: String)

@Serializable
data class EmbedRequest(val text: String, val taskType: String = "RETRIEVAL_DOCUMENT")

@Serializable
data class EmbedResponse(val dimension: Int, val preview: List<Double>, val taskType: String)

@Serializable
data class SearchRequest(val query: String, val collection: String = "chunks_demo", val limit: Int = 5)

@Serializable
data class SearchResponse(val results: List<SearchResultItem>)

@Serializable
data class SearchResultItem(val score: Float, val payload: Map<String, String>)

@Serializable
data class ParseRequest(val code: String)

@Serializable
data class ParseResponse(val functions: List<ParsedFunction>, val classes: List<String>, val nodeCount: Int)

@Serializable
data class ParsedFunction(
    val name: String,
    val receiver: String? = null,
    val parameters: String,
    val startLine: Int,
    val endLine: Int,
    val bodyLength: Int
)

@Serializable
data class ErrorResponse(val error: String)

// --- API routing ---

fun Application.configureApiRouting() {
    // Lazy-init shared resources (nullable if API key is missing)
    val apiKey: String? = System.getenv("NTIVO_GEMINI_API_KEY")

    val agent: AIAgent<String, String>? = apiKey?.let {
        AIAgent(
            promptExecutor = simpleGoogleAIExecutor(it),
            llmModel = GoogleModels.Gemini2_5Flash,
            systemPrompt = "You are a helpful assistant that knows about codebases. " +
                "You help engineers understand code, trace features across repositories, " +
                "and answer questions about software architecture."
        )
    }

    val embedder: GeminiEmbedder? = apiKey?.let { GeminiEmbedder(it) }

    routing {
        route("/api") {

            // POST /api/chat — Send a prompt to the Koog agent
            post("/chat") {
                val request = call.receive<ChatRequest>()
                if (request.prompt.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("'prompt' is required and cannot be blank."))
                }
                val agentInstance = agent
                    ?: return@post call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("NTIVO_GEMINI_API_KEY not set. Export it and restart the server."))
                try {
                    val result = agentInstance.run(request.prompt)
                    call.respond(ChatResponse(response = result))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway,
                        ErrorResponse("Gemini error: ${e.message}"))
                }
            }

            // POST /api/embed — Embed text with a specific task type
            post("/embed") {
                val request = call.receive<EmbedRequest>()
                if (request.text.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("'text' is required and cannot be blank."))
                }
                val taskType = try {
                    EmbeddingTaskType.valueOf(request.taskType)
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest,
                        ErrorResponse("Invalid taskType '${request.taskType}'. " +
                            "Valid: ${EmbeddingTaskType.entries.joinToString()}"))
                }
                val emb = embedder
                    ?: return@post call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("NTIVO_GEMINI_API_KEY not set. Export it and restart the server."))
                try {
                    val vector = emb.embed(request.text, taskType)
                    call.respond(EmbedResponse(
                        dimension = vector.dimension,
                        preview = vector.values.take(10),
                        taskType = taskType.name
                    ))
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadGateway,
                        ErrorResponse("Embedding error: ${e.message}"))
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
                    ?: return@post call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("NTIVO_GEMINI_API_KEY not set. Export it and restart the server."))

                val safeLimit = request.limit.coerceIn(1, 20)
                val qdrant: QdrantClient
                try {
                    qdrant = QdrantClient(
                        QdrantGrpcClient.newBuilder("localhost", 6334, false).build()
                    )
                } catch (e: Exception) {
                    return@post call.respond(HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Cannot connect to Qdrant at localhost:6334. Run: docker compose up -d"))
                }

                try {
                    // Check collection exists
                    val collections = qdrant.listCollectionsAsync().await()
                    if (!collections.contains(request.collection)) {
                        return@post call.respond(HttpStatusCode.NotFound,
                            ErrorResponse("Collection '${request.collection}' not found. " +
                                "Run the embedding demo first: NTIVO_GEMINI_API_KEY=\"...\" ./gradlew runEmbeddingDemo"))
                    }

                    val queryVector = emb.embed(request.query, EmbeddingTaskType.CODE_RETRIEVAL_QUERY)
                    val queryFloats = queryVector.values.map { it.toFloat() }

                    val results = qdrant.searchAsync(
                        SearchPoints.newBuilder()
                            .setCollectionName(request.collection)
                            .addAllVector(queryFloats)
                            .setLimit(safeLimit.toLong())
                            .setWithPayload(WithPayloadSelectorFactory.enable(true))
                            .build()
                    ).await()

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
                        ErrorResponse("Search error: ${e.message}"))
                } finally {
                    qdrant.close()
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
                    val parser = TSParser()
                    parser.setLanguage(TreeSitterKotlin())
                    val tree = parser.parseString(null, request.code)
                    val root = tree.rootNode

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
        }
    }
}
