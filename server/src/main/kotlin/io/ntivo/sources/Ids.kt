package io.ntivo.sources

import io.ntivo.FunctionChunk
import java.util.UUID

/** A short random id for a new source: 12 hex chars. */
fun newSourceId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

/**
 * Stable id for a piece, also used as its Qdrant point id. Includes the source id
 * and path so two repositories with the same file and function never collide.
 * Same as `pieceId(sourceId, path, chunk, 0)`: right for a function whose signature
 * is unique in its file. Use [pieceIds] for a whole file.
 */
fun pieceId(sourceId: String, path: String, chunk: FunctionChunk): String = pieceId(sourceId, path, chunk, 0)

/**
 * Stable id for the [ordinal]-th function (0-based, in source order) with [chunk]'s
 * signature in its file, e.g. `override fun log()` in two anonymous objects. Ordinal 0
 * hashes the plain signature, so ids of unique functions do not change.
 */
fun pieceId(sourceId: String, path: String, chunk: FunctionChunk, ordinal: Int): String {
    require(ordinal >= 0) { "ordinal must be >= 0, was $ordinal" }
    val suffix = if (ordinal > 0) "#$ordinal" else ""
    return UUID.nameUUIDFromBytes("$sourceId|$path|${signatureKey(chunk)}$suffix".toByteArray()).toString()
}

/**
 * For each function of one file, in list (source) order, how many earlier functions
 * share its signature: 0 for the first, 1 for the second, and so on.
 */
fun pieceOrdinals(functions: List<FunctionChunk>): List<Int> {
    val seen = HashMap<String, Int>()
    return functions.map { fn ->
        val key = signatureKey(fn)
        val ordinal = seen[key] ?: 0
        seen[key] = ordinal + 1
        ordinal
    }
}

/** Piece ids for all functions of one file, in the same order; distinct even for repeated signatures. */
fun pieceIds(sourceId: String, path: String, functions: List<FunctionChunk>): List<String> =
    functions.zip(pieceOrdinals(functions)) { fn, ordinal -> pieceId(sourceId, path, fn, ordinal) }

private fun signatureKey(chunk: FunctionChunk): String =
    "${chunk.container.orEmpty()}#${chunk.receiver.orEmpty()}.${chunk.name}${chunk.parameters}"

/** Graph node id, e.g. `FUNCTION:3f2a9c1d0b7e:app/Repo.kt#Repo.load`. */
fun nodeId(sourceId: String, kind: String, path: String, qualified: String): String =
    "$kind:$sourceId:$path#$qualified"
