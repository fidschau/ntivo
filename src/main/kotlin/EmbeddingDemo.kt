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
 * Embedding demo that proves the full pipeline works:
 * 1. Embeds a Kotlin code snippet using Gemini (with taskType)
 * 2. Stores the vector in Qdrant (chunks_demo collection)
 * 3. Queries Qdrant with a natural language question
 *
 * Run with: NTIVO_GEMINI_API_KEY="..." ./gradlew runEmbeddingDemo
 * Requires: Qdrant running on localhost:6334 (docker compose up -d)
 */
fun main() = runBlocking {
    val apiKey = System.getenv("NTIVO_GEMINI_API_KEY")
        ?: error("Set the NTIVO_GEMINI_API_KEY environment variable.")

    val embedder = GeminiEmbedder(apiKey)
    val collectionName = "chunks_demo"

    // --- 1. Embed a code snippet ---
    val codeSnippet = """
        fun Application.configureRouting() {
            routing {
                get("/health") {
                    call.respond(HealthResponse(status = "ok"))
                }
            }
        }
    """.trimIndent()

    println("Embedding code snippet (${codeSnippet.length} chars)...")
    val codeVector = embedder.embed(codeSnippet, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
    println("Got ${codeVector.dimension}-dimensional vector")
    println("  First 5 values: ${codeVector.values.take(5).map { "%.6f".format(it) }}")

    // --- 2. Store in Qdrant ---
    val qdrant = QdrantClient(
        QdrantGrpcClient.newBuilder("localhost", 6334, false).build()
    )

    // Create collection (skip if already exists)
    val collections = qdrant.listCollectionsAsync().await()
    if (collections.any { it == collectionName }) {
        println("Collection $collectionName already exists, reusing")
    } else {
        qdrant.createCollectionAsync(
            collectionName,
            VectorParams.newBuilder()
                .setDistance(Distance.Cosine)
                .setSize(3072)  // gemini-embedding-001 dimensions
                .build()
        ).await()
        println("Created Qdrant collection: $collectionName (3072 dims, cosine)")
    }

    // Upsert the vector with metadata
    val floatValues = codeVector.values.map { it.toFloat() }
    qdrant.upsertAsync(
        collectionName,
        listOf(
            PointStruct.newBuilder()
                .setId(id(1))
                .setVectors(vectors(floatValues))
                .putAllPayload(
                    mapOf(
                        "source_file" to value("Routing.kt"),
                        "function_name" to value("configureRouting"),
                        "org_id" to value("demo"),
                        "language" to value("kotlin")
                    )
                )
                .build()
        )
    ).await()
    println("Stored vector in Qdrant (point id: 1)")

    // --- 3. Query with natural language ---
    println("\nSearching for: 'health check endpoint'...")
    val queryVector = embedder.embed("health check endpoint", EmbeddingTaskType.CODE_RETRIEVAL_QUERY)
    val queryFloats = queryVector.values.map { it.toFloat() }

    val results = qdrant.searchAsync(
        SearchPoints.newBuilder()
            .setCollectionName(collectionName)
            .addAllVector(queryFloats)
            .setLimit(3)
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build()
    ).await()

    println("Results (${results.size} hits):")
    for (result in results) {
        println("  Score: %.4f".format(result.score))
        result.payloadMap.forEach { (key, v) ->
            println("    $key = ${v.stringValue}")
        }
    }

    // --- 4. Verify semantic understanding ---
    println("\nSearching for: 'server status monitoring'...")
    val queryVector2 = embedder.embed("server status monitoring", EmbeddingTaskType.CODE_RETRIEVAL_QUERY)
    val queryFloats2 = queryVector2.values.map { it.toFloat() }

    val results2 = qdrant.searchAsync(
        SearchPoints.newBuilder()
            .setCollectionName(collectionName)
            .addAllVector(queryFloats2)
            .setLimit(3)
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
            .build()
    ).await()

    println("Results (${results2.size} hits):")
    for (result in results2) {
        println("  Score: %.4f".format(result.score))
        result.payloadMap.forEach { (key, v) ->
            println("    $key = ${v.stringValue}")
        }
    }

    // Cleanup
    embedder.close()
    qdrant.close()
    println("\nEmbedding demo complete!")
}
