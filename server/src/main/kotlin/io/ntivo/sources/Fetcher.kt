package io.ntivo.sources

import io.ntivo.parse.Language
import java.io.File

/** What a fetcher put on disk: the working directory, the supported files in it, and a display name. */
data class FetchResult(val dir: File, val files: List<File>, val name: String)

/** Stage 1 of an index job: puts a source's files on disk under `into`. */
interface Fetcher {
    suspend fun fetch(into: File, onProgress: (done: Int, total: Int, message: String) -> Unit): FetchResult
}

/** Folders never read: build output, VCS metadata and vendored dependencies. */
private val SKIPPED_DIRS = setOf("build", ".git", "Pods", "node_modules", ".gradle", ".build", "DerivedData")

private val SUPPORTED_EXTENSIONS = Language.entries.flatMap { it.extensions }.toSet()

/** Every file under [root] in a supported language, outside skipped folders, in a stable order. */
fun supportedFiles(root: File): List<File> =
    root.walkTopDown()
        .onEnter { it == root || it.name !in SKIPPED_DIRS }
        .filter { it.isFile && it.extension.lowercase() in SUPPORTED_EXTENSIONS }
        .sortedBy { it.path }
        .toList()
