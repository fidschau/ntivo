package io.ntivo

import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.WithPayloadSelectorFactory
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.VectorParams
import io.qdrant.client.grpc.JsonWithInt.Value
import io.qdrant.client.grpc.Points.PointStruct
import io.qdrant.client.grpc.Points.ScoredPoint
import io.qdrant.client.grpc.Points.SearchPoints
import kotlinx.coroutines.guava.await
import java.security.MessageDigest
import java.util.UUID

/** gemini-embedding-001 output size. Changing embedding model means a new collection. */
const val EMBEDDING_DIMENSIONS = 3072L

/**
 * Thin wrapper over one shared Qdrant gRPC client.
 * The client is thread-safe and meant to be reused, not created per request.
 */
class VectorStore(
    host: String = System.getenv("NTIVO_QDRANT_HOST") ?: "localhost",
    port: Int = 6334
) {
    private val client = QdrantClient(QdrantGrpcClient.newBuilder(host, port, false).build())

    suspend fun collectionExists(collection: String): Boolean =
        client.collectionExistsAsync(collection).await()

    suspend fun ensureCollection(collection: String) {
        if (collectionExists(collection)) return
        client.createCollectionAsync(
            collection,
            VectorParams.newBuilder()
                .setDistance(Distance.Cosine)
                .setSize(EMBEDDING_DIMENSIONS)
                .build()
        ).await()
    }

    /** Returns the stored `fingerprint` payload for each of [ids] that already exists. */
    suspend fun fingerprints(collection: String, ids: List<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        val points = client.retrieveAsync(collection, ids.map { id(it) }, true, false, null).await()
        return points.associate { p ->
            UUID.fromString(p.id.uuid) to (p.payloadMap["fingerprint"]?.stringValue ?: "")
        }
    }

    suspend fun upsert(collection: String, pointId: UUID, vector: List<Double>, payload: Map<String, Value>) {
        client.upsertAsync(
            collection,
            listOf(
                PointStruct.newBuilder()
                    .setId(id(pointId))
                    .setVectors(vectors(vector.map { it.toFloat() }))
                    .putAllPayload(payload)
                    .build()
            )
        ).await()
    }

    suspend fun search(collection: String, vector: List<Double>, limit: Int): List<ScoredPoint> =
        client.searchAsync(
            SearchPoints.newBuilder()
                .setCollectionName(collection)
                .addAllVector(vector.map { it.toFloat() })
                .setLimit(limit.toLong())
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build()
        ).await()

    fun close() = client.close()
}

/** Stable point ID for a function, so re-indexing overwrites instead of duplicating. */
fun FunctionChunk.pointId(): UUID =
    UUID.nameUUIDFromBytes("${container.orEmpty()}#${receiver.orEmpty()}.$name$parameters".toByteArray())

/** SHA-256 of the function body. Unchanged fingerprint means the stored embedding is still valid. */
fun FunctionChunk.fingerprint(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(body.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun FunctionChunk.payload(): Map<String, Value> = mapOf(
    "text" to value(body.take(2000)),
    "label" to value(name),
    "source" to value("rag_pipeline"),
    "receiver" to value(receiver ?: ""),
    "parameters" to value(parameters),
    "startLine" to value(startLine.toLong()),
    "endLine" to value(endLine.toLong()),
    "fingerprint" to value(fingerprint())
)
