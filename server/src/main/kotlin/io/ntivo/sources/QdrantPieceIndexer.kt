package io.ntivo.sources

import io.ntivo.EmbeddingRateLimited
import io.ntivo.EmbeddingRequestFailed
import io.ntivo.EmbeddingTaskType
import io.ntivo.TaskTypeEmbedder
import io.ntivo.VectorStore
import io.ntivo.model.Piece
import io.ntivo.payload
import io.qdrant.client.ValueFactory.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.random.Random

/**
 * Stage 3: embeds pieces as `RETRIEVAL_DOCUMENT` and stores them in the shared Qdrant
 * [collection], one point per piece (point id = piece id).
 *
 * Pieces are handled in batches of [batchSize]. For each batch the stored fingerprints are
 * read first and only new or changed pieces are embedded, in one [TaskTypeEmbedder.embedAll]
 * call. Progress is reported once per batch over all pieces, embedded or skipped, and again
 * (unchanged) before every backoff wait so the stage shows it is still alive.
 *
 * Failures are retried up to [maxAttempts] times:
 * - [EmbeddingRateLimited] waits the `retryAfterSeconds` the API asked for, else backs off
 *   exponentially with jitter from [baseDelayMs], capped at [maxRetryDelayMs]. When the
 *   attempts run out it is rethrown (still an [EmbeddingRateLimited]) so the stage fails
 *   with the rate-limit message. A hint longer than [MAX_RETRY_AFTER_SECONDS] means the
 *   daily quota is spent, so it fails at once instead of waiting.
 * - [EmbeddingRequestFailed] with a 4xx status (bad key, bad request) is not retried.
 * - Anything else (5xx, network) backs off the same way and ends in [IllegalStateException].
 *
 * The stored fingerprint covers the text and its location (path, lines, names), so a
 * function that only moved is written again and its citation lines stay right.
 */
class QdrantPieceIndexer(
    private val store: VectorStore,
    private val embedder: TaskTypeEmbedder,
    private val collection: String,
    private val batchSize: Int = embedder.maxBatch,
    private val maxAttempts: Int = 8,
    private val baseDelayMs: Long = 2_000,
    private val maxRetryDelayMs: Long = 60_000,
    private val random: Random = Random.Default,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
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
                val vectors = embedWithRetry(sourceId, changed.map(::embeddingText)) { onProgress(processed, pieces.size) }
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
     * Embeds [texts] in one call, retrying as described on the class. [onWait] runs before
     * every backoff wait. With the defaults the waits add up to about four minutes, long
     * enough to outlast a per-minute quota (the Gemini free tier allows 100 a minute) or
     * a local model server that is still starting.
     */
    private suspend fun embedWithRetry(sourceId: String, texts: List<String>, onWait: () -> Unit): List<List<Double>> {
        var attempt = 0
        while (true) {
            try {
                val vectors = embedder.embedAll(texts, EmbeddingTaskType.RETRIEVAL_DOCUMENT)
                check(vectors.size == texts.size) { "Embedder returned ${vectors.size} vectors for ${texts.size} texts" }
                return vectors.map { it.values }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EmbeddingRequestFailed) {
                if (e.status != null && e.status in 400..499) throw e
                attempt++
                if (attempt >= maxAttempts) throw gaveUp(attempt, e)
                wait(sourceId, attempt, backoffMs(attempt), e, onWait)
            } catch (e: EmbeddingRateLimited) {
                if (e.retryAfterSeconds != null && e.retryAfterSeconds > MAX_RETRY_AFTER_SECONDS) throw quotaExhausted(e)
                attempt++
                if (attempt >= maxAttempts) {
                    throw EmbeddingRateLimited(
                        e.retryAfterSeconds,
                        "Could not capture meaning after $attempt attempts. ${e.message}",
                        e
                    )
                }
                val waitMs = e.retryAfterSeconds?.let { minOf(it * 1_000L, maxRetryDelayMs) } ?: backoffMs(attempt)
                wait(sourceId, attempt, waitMs, e, onWait)
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxAttempts) throw gaveUp(attempt, e)
                wait(sourceId, attempt, backoffMs(attempt), e, onWait)
            }
        }
    }

    private suspend fun wait(sourceId: String, attempt: Int, waitMs: Long, cause: Exception, onWait: () -> Unit) {
        log.warn(
            "Source {}: embedding attempt {} of {} failed ({}); retrying in {} ms",
            sourceId, attempt, maxAttempts, cause.message, waitMs
        )
        onWait()
        sleep(waitMs)
    }

    private fun quotaExhausted(e: EmbeddingRateLimited) = EmbeddingRateLimited(
        e.retryAfterSeconds,
        "The Gemini embedding quota is exhausted. The API asked to wait " +
            "${humanDuration(e.retryAfterSeconds!!)}; retry indexing then.",
        e
    )

    private fun gaveUp(attempt: Int, e: Exception) = IllegalStateException(
        "Could not capture meaning after $attempt attempts. The model service may be " +
            "rate limiting this key or out of quota. Last error: ${e.message}",
        e
    )

    /**
     * Exponential backoff with jitter: the ceiling doubles from [baseDelayMs] per attempt up
     * to [maxRetryDelayMs], and the wait is a random point in the upper half of it.
     */
    private fun backoffMs(attempt: Int): Long {
        val ceiling = minOf(baseDelayMs shl (attempt - 1).coerceIn(0, 30), maxRetryDelayMs)
        return ceiling / 2 + random.nextLong(ceiling / 2 + 1)
    }

    companion object {
        /** A Retry-After above this (five minutes) means the daily quota is spent, not a per-minute limit. */
        const val MAX_RETRY_AFTER_SECONDS = 300

        /** "about 4 minutes" below an hour, else "about 13.6 hours". */
        internal fun humanDuration(seconds: Int): String =
            if (seconds < 3_600) {
                val minutes = maxOf(1, Math.round(seconds / 60.0).toInt())
                "about $minutes ${if (minutes == 1) "minute" else "minutes"}"
            } else {
                "about ${String.format(Locale.ROOT, "%.1f", seconds / 3_600.0)} hours"
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
