package io.ntivo.sources

import java.io.File

/** What a fetcher put on disk: the working directory, the supported files in it, and a display name. */
data class FetchResult(val dir: File, val files: List<File>, val name: String)

/** Stage 1 of an index job: puts a source's files on disk under `into`. */
interface Fetcher {
    suspend fun fetch(into: File, onProgress: (done: Int, total: Int, message: String) -> Unit): FetchResult
}
