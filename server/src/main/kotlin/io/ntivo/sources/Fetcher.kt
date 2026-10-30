package io.ntivo.sources

import io.ntivo.parse.Language
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/** What a fetcher put on disk: the working directory, the supported files in it, and a display name. */
data class FetchResult(val dir: File, val files: List<File>, val name: String)

/** Stage 1 of an index job: puts a source's files on disk under `into`. */
interface Fetcher {
    suspend fun fetch(into: File, onProgress: (done: Int, total: Int, message: String) -> Unit): FetchResult
}

/** Folders never read: build output, VCS metadata and vendored dependencies. */
private val SKIPPED_DIRS = setOf("build", ".git", "Pods", "node_modules", ".gradle", ".build", "DerivedData")

private val SUPPORTED_EXTENSIONS = Language.entries.flatMap { it.extensions }.toSet()

/**
 * Every file under [root] in a supported language, outside skipped folders, in a stable order.
 *
 * Symbolic links are never followed or returned: a cloned repository can contain links
 * pointing anywhere on this machine, and reading through them would index local files
 * that are not part of the source.
 */
fun supportedFiles(root: File): List<File> =
    root.walkTopDown()
        .onEnter { it == root || (it.name !in SKIPPED_DIRS && !Files.isSymbolicLink(it.toPath())) }
        .filter {
            !Files.isSymbolicLink(it.toPath()) &&
                Files.isRegularFile(it.toPath(), LinkOption.NOFOLLOW_LINKS) &&
                it.extension.lowercase() in SUPPORTED_EXTENSIONS
        }
        .sortedBy { it.path }
        .toList()

/**
 * Deletes a working copy without following symbolic links: a link inside it is removed
 * itself, never its target. [File.deleteRecursively] follows linked directories and would
 * delete files outside the data folder when a repository contains such a link.
 */
@OptIn(ExperimentalPathApi::class)
fun deleteWorkingCopy(dir: File) {
    val path = dir.toPath()
    if (Files.isSymbolicLink(path)) Files.delete(path) else if (Files.exists(path)) path.deleteRecursively()
}
