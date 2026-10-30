package io.ntivo.sources

import io.ntivo.FunctionChunk
import java.util.UUID

/** A short random id for a new source: 12 hex chars. */
fun newSourceId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

/**
 * Stable id for a piece, also used as its Qdrant point id. Includes the source id
 * and path so two repositories with the same file and function never collide.
 */
fun pieceId(sourceId: String, path: String, chunk: FunctionChunk): String =
    UUID.nameUUIDFromBytes(
        "$sourceId|$path|${chunk.container.orEmpty()}#${chunk.receiver.orEmpty()}.${chunk.name}${chunk.parameters}"
            .toByteArray()
    ).toString()

/** Graph node id, e.g. `FUNCTION:3f2a9c1d0b7e:app/Repo.kt#Repo.load`. */
fun nodeId(sourceId: String, kind: String, path: String, qualified: String): String =
    "$kind:$sourceId:$path#$qualified"
