package io.ntivo

import io.ntivo.model.Piece
import io.qdrant.client.ConditionFactory.matchKeywords
import io.qdrant.client.PointIdFactory.id
import io.qdrant.client.QdrantClient
import io.qdrant.client.QdrantGrpcClient
import io.qdrant.client.ValueFactory.value
import io.qdrant.client.VectorsFactory.vectors
import io.qdrant.client.WithPayloadSelectorFactory
import io.qdrant.client.grpc.Collections.Distance
import io.qdrant.client.grpc.Collections.PayloadSchemaType
import io.qdrant.client.grpc.Common.Filter
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

    /**
     * Creates [collection] (cosine, [dimensions]) if missing, with a keyword index on
     * `sourceId` so per-source filters and deletes stay fast on large collections.
     */
    suspend fun ensureCollection(collection: String, dimensions: Long = EMBEDDING_DIMENSIONS) {
        if (collectionExists(collection)) return
        client.createCollectionAsync(
            collection,
            VectorParams.newBuilder()
                .setDistance(Distance.Cosine)
                .setSize(dimensions)
                .build()
        ).await()
        client.createPayloadIndexAsync(
            collection, "sourceId", PayloadSchemaType.Keyword, null, true, null, null
        ).await()
    }

    suspend fun deleteCollection(collection: String) {
        client.deleteCollectionAsync(collection).await()
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

    /** Writes many points in one request. Each entry is point id to (vector, payload). */
    suspend fun upsertBatch(
        collection: String,
        points: List<Pair<UUID, Pair<List<Double>, Map<String, Value>>>>
    ) {
        if (points.isEmpty()) return
        client.upsertAsync(
            collection,
            points.map { (pointId, vectorAndPayload) ->
                val (vector, payload) = vectorAndPayload
                PointStruct.newBuilder()
                    .setId(id(pointId))
                    .setVectors(vectors(vector.map { it.toFloat() }))
                    .putAllPayload(payload)
                    .build()
            }
        ).await()
    }

    /** Removes every point whose payload `sourceId` is [sourceId]. */
    suspend fun deleteBySource(collection: String, sourceId: String) {
        if (!collectionExists(collection)) return
        client.deleteAsync(collection, sourceFilter(listOf(sourceId))).await()
    }

    /**
     * Nearest points to [vector]. With [sourceIds], only points from those sources are
     * considered (an empty list matches nothing). Points scoring below [minScore] are dropped.
     */
    suspend fun search(
        collection: String,
        vector: List<Double>,
        limit: Int,
        sourceIds: List<String>? = null,
        minScore: Float = 0f
    ): List<ScoredPoint> {
        if (sourceIds != null && sourceIds.isEmpty()) return emptyList()
        val request = SearchPoints.newBuilder()
            .setCollectionName(collection)
            .addAllVector(vector.map { it.toFloat() })
            .setLimit(limit.toLong())
            .setWithPayload(WithPayloadSelectorFactory.enable(true))
        if (sourceIds != null) request.setFilter(sourceFilter(sourceIds))
        if (minScore > 0f) request.setScoreThreshold(minScore)
        return client.searchAsync(request.build()).await()
    }

    /** The piece stored under [pieceId], or null if it is missing or not a piece point. */
    suspend fun getPiece(collection: String, pieceId: String): Piece? {
        val uuid = runCatching { UUID.fromString(pieceId) }.getOrNull() ?: return null
        val point = client.retrieveAsync(collection, listOf(id(uuid)), true, false, null).await()
            .firstOrNull() ?: return null
        if (!point.payloadMap.containsKey("sourceId")) return null
        return pieceFromPayload(point.payloadMap)
    }

    private fun sourceFilter(sourceIds: List<String>): Filter =
        Filter.newBuilder().addMust(matchKeywords("sourceId", sourceIds)).build()

    fun close() = client.close()
}

/** Stable point ID for a function, so re-indexing overwrites instead of duplicating. */
fun FunctionChunk.pointId(): UUID =
    UUID.nameUUIDFromBytes("${container.orEmpty()}#${receiver.orEmpty()}.$name$parameters".toByteArray())

/** SHA-256 of the function body. Unchanged fingerprint means the stored embedding is still valid. */
fun FunctionChunk.fingerprint(): String = sha256(body)

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

/** SHA-256 of the piece text. Unchanged fingerprint means the stored embedding is still valid. */
fun Piece.fingerprint(): String = sha256(text)

/**
 * Qdrant payload for a piece. Keys: pieceId, sourceId, sourceName, language, path, name,
 * qualifiedName, startLine, endLine, text, fingerprint.
 */
fun Piece.payload(): Map<String, Value> = mapOf(
    "pieceId" to value(id),
    "sourceId" to value(sourceId),
    "sourceName" to value(sourceName),
    "language" to value(language),
    "path" to value(path),
    "name" to value(name),
    "qualifiedName" to value(qualifiedName),
    "startLine" to value(startLine.toLong()),
    "endLine" to value(endLine.toLong()),
    "text" to value(text),
    "fingerprint" to value(fingerprint())
)

/** Inverse of [Piece.payload]. Missing keys become empty strings or zero. */
fun pieceFromPayload(map: Map<String, Value>): Piece {
    fun str(key: String) = map[key]?.stringValue.orEmpty()
    fun int(key: String) = map[key]?.integerValue?.toInt() ?: 0
    return Piece(
        id = str("pieceId"),
        sourceId = str("sourceId"),
        sourceName = str("sourceName"),
        language = str("language"),
        path = str("path"),
        name = str("name"),
        qualifiedName = str("qualifiedName"),
        startLine = int("startLine"),
        endLine = int("endLine"),
        text = str("text")
    )
}

private fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
