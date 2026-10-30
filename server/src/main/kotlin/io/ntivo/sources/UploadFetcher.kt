package io.ntivo.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** One uploaded file. [relativePath] is the browser's `webkitRelativePath`, e.g. `app/src/Repo.kt`. */
data class UploadedFile(val relativePath: String, val bytes: ByteArray)

/**
 * Writes uploaded files under the working directory. Paths are checked up front, so a bad
 * upload is rejected (with [IllegalArgumentException]) before any job starts.
 */
class UploadFetcher(private val name: String, private val parts: List<UploadedFile>) : Fetcher {

    init {
        parts.forEach { requireSafePath(it.relativePath) }
    }

    override suspend fun fetch(
        into: File,
        onProgress: (done: Int, total: Int, message: String) -> Unit
    ): FetchResult = withContext(Dispatchers.IO) {
        deleteWorkingCopy(into)
        into.mkdirs()
        val root = into.canonicalFile
        val total = parts.size
        onProgress(0, total, "Saving $total files")
        parts.forEachIndexed { i, part ->
            ensureActive()
            val target = File(root, normalise(part.relativePath)).canonicalFile
            // Second line of defence after requireSafePath: never write outside the root
            require(target.toPath().startsWith(root.toPath())) { "Invalid path: ${part.relativePath}" }
            target.parentFile.mkdirs()
            target.writeBytes(part.bytes)
            if ((i + 1) % PROGRESS_EVERY == 0) onProgress(i + 1, total, "Saved ${i + 1} files")
        }
        val files = supportedFiles(into)
        onProgress(total, total, "Saved $total files")
        FetchResult(dir = into, files = files, name = name)
    }

    override fun toString() = "UploadFetcher(name=$name, files=${parts.size})"

    private companion object {
        const val PROGRESS_EVERY = 100

        fun normalise(path: String) = path.replace('\\', '/').trimStart('/')

        fun requireSafePath(path: String) {
            val p = path.replace('\\', '/')
            require(p.isNotBlank() && !p.startsWith("/") && p.split('/').none { it == ".." } && ':' !in p) {
                "Invalid path: $path"
            }
        }
    }
}
