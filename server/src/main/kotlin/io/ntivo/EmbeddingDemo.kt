package io.ntivo

import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.VectorParams
import io.qdrant.client.grpc.Points.PointStruct
import io.qdrant.client.grpc.Points.SearchPoints
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.WithPayloadSelectorFactory
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.runBlocking

/**
 * Embedding demo that seeds Qdrant with diverse code snippets,
 * then performs semantic searches to prove the pipeline works.
 *
 * Stores 6 different Kotlin snippets covering different concepts.
 * Each search query should return different results based on meaning.
 *
 * Run with: NTIVO_GEMINI_API_KEY="..." ./gradlew runEmbeddingDemo
 * Requires: Qdrant running on localhost:6334 (docker compose up -d)
 */

// Each snippet has an ID, a label, and the actual code
data class DemoSnippet(val id: Long, val label: String, val code: String)

private val DEMO_SNIPPETS = listOf(
    DemoSnippet(
        id = 1,
        label = "health check endpoint",
        code = """
            fun Application.configureRouting() {
                routing {
                    get("/health") {
                        call.respond(HealthResponse(status = "ok"))
                    }
                }
            }
        """.trimIndent()
    ),
    DemoSnippet(
        id = 2,
        label = "user authentication",
        code = """
            suspend fun authenticateUser(credentials: LoginRequest): AuthResult {
                val user = userRepository.findByEmail(credentials.email)
                    ?: return AuthResult.NotFound
                if (!passwordHasher.verify(credentials.password, user.passwordHash)) {
                    return AuthResult.InvalidPassword
                }
                val token = jwtService.generateToken(user.id, user.roles)
                return AuthResult.Success(token, user.displayName)
            }
        """.trimIndent()
    ),
    DemoSnippet(
        id = 3,
        label = "database connection pool",
        code = """
            fun configureDatabasePool(config: DatabaseConfig): HikariDataSource {
                val hikariConfig = HikariConfig().apply {
                    jdbcUrl = config.url
                    username = config.username
                    password = config.password
                    maximumPoolSize = config.maxPoolSize
                    connectionTimeout = 30_000
                    idleTimeout = 600_000
                    isAutoCommit = false
                }
                return HikariDataSource(hikariConfig)
            }
        """.trimIndent()
    ),
    DemoSnippet(
        id = 4,
        label = "file upload handler",
        code = """
            suspend fun handleFileUpload(call: ApplicationCall) {
                val multipart = call.receiveMultipart()
                var fileName = ""
                var fileBytes = ByteArray(0)
                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            fileName = part.originalFileName ?: "unknown"
                            fileBytes = part.streamProvider().readBytes()
                        }
                        else -> {}
                    }
                    part.dispose()
                }
                val saved = storageService.save(fileName, fileBytes)
                call.respond(UploadResponse(path = saved.path, size = fileBytes.size))
            }
        """.trimIndent()
    ),
    DemoSnippet(
        id = 5,
        label = "retry with exponential backoff",
        code = """
            suspend fun <T> retryWithBackoff(
                maxRetries: Int = 3,
                initialDelay: Long = 1000,
                maxDelay: Long = 30_000,
                factor: Double = 2.0,
                block: suspend () -> T
            ): T {
                var currentDelay = initialDelay
                repeat(maxRetries - 1) { attempt ->
                    try { return block() }
                    catch (e: Exception) {
                        logger.warn("Attempt {} failed: {}", attempt + 1, e.message)
                    }
                    delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
                }
                return block() // last attempt, let exception propagate
            }
        """.trimIndent()
    ),
    DemoSnippet(
        id = 6,
        label = "JSON serialization config",
        code = """
            fun Application.configureSerialization() {
                install(ContentNegotiation) {
                    json(Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                        coerceInputValues = true
                    })
                }
            }
        """.trimIndent()
    )
)

fun main() = runBlocking {
    val apiKey = System.getenv("NTIVO_GEMINI_API_KEY")
        ?: error("Set the NTIVO_GEMINI_API_KEY environment variable.")

    val embedder = GeminiEmbedder(apiKey)
    val collectionName = "chunks_demo"

    // --- 1. Connect to Qdrant ---
    val qdrant = QdrantClient(
        QdrantGrpcClient.newBuilder("localhost", 6334, false).build()
    )

    // Recreate collection fresh so demo is repeatable
    val collections = qdrant.listCollectionsAsync().await()
    if (collections.any { it == collectionName }) {
        println("Dropping existing $collectionName collection for fresh demo...")
        qdrant.deleteCollectionAsync(collectionName).await()
    }
    qdrant.createCollectionAsync(
        collectionName,
        VectorParams.newBuilder()
            .setDistance(Distance.Cosine)
            .setSize(3072)  // gemini-embedding-001 dimensions
            .build()
    ).await()
    println("Created Qdrant collection: $collectionName (3072 dims, cosine)\n")

    // --- 2. Embed and store all snippets ---
    println("Embedding ${DEMO_SNIPPETS.size} code snippets...")
    for (snippet in DEMO_SNIPPETS) {
        print("  [${snippet.id}/${DEMO_SNIPPETS.size}] ${snippet.label}... ")
        val vector = embedder.embed(snippet.code, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
        val floatValues = vector.values.map { it.toFloat() }

        qdrant.upsertAsync(
            collectionName,
            listOf(
                PointStruct.newBuilder()
                    .setId(id(snippet.id))
                    .setVectors(vectors(floatValues))
                    .putAllPayload(mapOf(
                        "text" to value(snippet.code),
                        "label" to value(snippet.label),
                        "source" to value("embedding_demo"),
                        "language" to value("kotlin")
                    ))
                    .build()
            )
        ).await()
        println("stored (${vector.dimension}d)")
    }
    println("\nAll ${DEMO_SNIPPETS.size} snippets stored!\n")

    // --- 3. Test semantic search ---
    val testQueries = listOf(
        "health check endpoint",
        "login and password verification",
        "retry failed network requests",
        "upload a file to the server",
        "configure JSON parsing"
    )

    for (query in testQueries) {
        println("Searching: \"$query\"")
        val queryVector = embedder.embed(query, EmbeddingTaskType.CODE_RETRIEVAL_QUERY)
        val queryFloats = queryVector.values.map { it.toFloat() }

        val results = qdrant.searchAsync(
            SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .addAllVector(queryFloats)
                .setLimit(3)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build()
        ).await()

        for (result in results) {
            val label = result.payloadMap["label"]?.stringValue ?: "?"
            println("  Score: %.4f  ->  %s".format(result.score, label))
        }
        println()
    }

    // Cleanup
    embedder.close()
    qdrant.close()
    println("Embedding demo complete! Now try searching in the dev console at http://localhost:8080")
}
