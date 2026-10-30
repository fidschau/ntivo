package io.ntivo.sources

import io.ntivo.EmbeddingTaskType
import io.ntivo.TaskTypeEmbedder
import io.ntivo.VectorStore
import io.ntivo.model.Piece
import io.ntivo.payload
import io.qdrant.client.ValueFactory.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID

/**
 * Stage 3: embeds pieces as `RETRIEVAL_DOCUMENT` and stores them in the shared Qdrant
 * [collection], one point per piece (point id = piece id).
 *
 * Pieces are handled in batches of [batchSize]. For each batch the stored fingerprints are
 * read first and only new or changed pieces are embedded; the requests in a batch run in
 * parallel. Progress is reported once per batch over all pieces, embedded or skipped.
 *
 * The stored fingerprint covers the text and its location (path, lines, names), so a
 * function that only moved is written again and its citation lines stay right.
 */
class QdrantPieceIndexer(
    private val store: VectorStore,
    private val embedder: TaskTypeEmbedder,
    private val collection: String,
    private val batchSize: Int = 16,
    private val maxAttempts: Int = 8,
    private val retryDelayMs: Long = 1_000,
    private val maxRetryDelayMs: Long = 60_000
) : PieceIndexer {

    private val log = LoggerFactory.getLogger(QdrantPieceIndexer::class.java)

    override suspend fun index(
        sourceId: String,
        sourceName: String,
        pieces: List<Piece>,
        onProgress: (Int, Int) -> Unit
    ): Int {
        var collectionReady = store.collectionExists(collection)
        var embedded = 0
        var processed = 0

        for (batch in pieces.chunked(batchSize)) {
            val stored = if (collectionReady) store.fingerprints(collection, batch.map { UUID.fromString(it.id) }) else emptyMap()
            val changed = batch.filter { stored[UUID.fromString(it.id)] != it.storedFingerprint() }

            if (changed.isNotEmpty()) {
                val vectors = coroutineScope {
                    changed.map { piece -> async { embedWithRetry(embeddingText(piece)) } }.awaitAll()
                }
                if (!collectionReady) {
                    store.ensureCollection(collection, dimensions = vectors.first().size.toLong())
                    collectionReady = true
                }
                store.upsertBatch(
                    collection,
                    changed.zip(vectors).map { (piece, vector) ->
                        UUID.fromString(piece.id) to (vector to piece.payload() + ("fingerprint" to value(piece.storedFingerprint())))
                    }
                )
                embedded += changed.size
            }

            processed += batch.size
            onProgress(processed, pieces.size)
        }
        log.info("Source {}: embedded {} of {} pieces ({} unchanged)", sourceId, embedded, pieces.size, pieces.size - embedded)
        return embedded
    }

    /**
     * Retries transient failures with exponential backoff capped at [maxRetryDelayMs]. With the
     * defaults the last retry comes about two minutes after the first failure, long enough to
     * outlast a per-minute quota (the Gemini free tier allows 100 embedding requests a minute).
     */
    private suspend fun embedWithRetry(text: String): List<Double> {
        var attempt = 0
        while (true) {
            try {
                return embedder.embed(text, EmbeddingTaskType.RETRIEVAL_DOCUMENT).values
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxAttempts) {
                    throw IllegalStateException(
                        "Could not capture meaning after $attempt attempts. The model service may be " +
                            "rate limiting this key or out of quota. Last error: ${e.message}",
                        e
                    )
                }
                delay(minOf(retryDelayMs shl (attempt - 1), maxRetryDelayMs))
            }
        }
    }
}

/**
 * What is embedded for a piece: its file and qualified name on top of the code, so a
 * question about "the invite service" can match `InviteService.friendLimit` even when
 * the body never says so.
 */
internal fun embeddingText(piece: Piece): String =
    "${piece.path}\n${piece.qualifiedName}\n${piece.text}"

/** SHA-256 over everything stored for a piece. Same value means the stored point is current. */
internal fun Piece.storedFingerprint(): String {
    val material = listOf(sourceName, language, path, name, qualifiedName, startLine, endLine, text).joinToString("\u0000")
    return MessageDigest.getInstance("SHA-256")
        .digest(material.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
