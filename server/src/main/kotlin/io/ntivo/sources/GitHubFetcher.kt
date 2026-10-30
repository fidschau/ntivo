package io.ntivo.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Shallow-clones a GitHub repository with JGit.
 *
 * [token] is a personal access token for private repositories. It is held only in this
 * object (in memory, for the life of the job), passed to JGit as credentials, and never
 * logged or written to disk: it is not part of the clone URL, so it does not end up in
 * `.git/config` either.
 */
class GitHubFetcher(url: String, private val branch: String?, private val token: String?) : Fetcher {

    /** `owner/repo`. */
    val name: String

    private val cloneUrl: String

    init {
        val match = URL_PATTERN.matchEntire(url.trim())
        val owner = match?.groupValues?.get(1)
        val repo = match?.groupValues?.get(2)
        require(owner != null && repo != null && repo !in setOf(".", "..") && owner !in setOf(".", "..")) {
            "Only github.com URLs are supported"
        }
        name = "$owner/$repo"
        cloneUrl = "https://github.com/$owner/$repo.git"
    }

    override suspend fun fetch(
        into: File,
        onProgress: (done: Int, total: Int, message: String) -> Unit
    ): FetchResult {
        val job = coroutineContext.job
        return withContext(Dispatchers.IO) {
            deleteWorkingCopy(into)
            into.parentFile?.mkdirs()

            val command = Git.cloneRepository()
                .setURI(cloneUrl)
                .setDirectory(into)
                .setDepth(1)
                .setNoTags()
                .setProgressMonitor(JobProgressMonitor(job, onProgress))
            branch?.takeIf { it.isNotBlank() }?.let {
                command.setBranch("refs/heads/$it").setBranchesToClone(listOf("refs/heads/$it"))
            }
            token?.takeIf { it.isNotBlank() }?.let {
                command.setCredentialsProvider(UsernamePasswordCredentialsProvider("x-access-token", it))
            }
            try {
                command.call().use { }
            } catch (e: Exception) {
                // JGit messages can echo the URL but never the credentials; keep the message short
                throw IllegalStateException("Could not clone $name: ${e.message ?: e.javaClass.simpleName}", e)
            }

            val files = supportedFiles(into)
            onProgress(files.size, files.size, "Fetched ${files.size} files")
            FetchResult(dir = into, files = files, name = name)
        }
    }

    override fun toString() = "GitHubFetcher(name=$name, branch=$branch, token=${if (token.isNullOrBlank()) "none" else "***"})"

    /** Forwards JGit's per-phase progress ("Receiving objects", ...) and stops when the job is cancelled. */
    private class JobProgressMonitor(
        private val job: Job,
        private val onProgress: (Int, Int, String) -> Unit
    ) : ProgressMonitor {
        private var title = ""
        private var total = 0
        private var done = 0

        override fun start(totalTasks: Int) {}

        override fun beginTask(title: String, totalWork: Int) {
            this.title = title
            total = if (totalWork == ProgressMonitor.UNKNOWN) 0 else totalWork
            done = 0
            onProgress(0, total, title)
        }

        override fun update(completed: Int) {
            val before = done
            done += completed
            // Report about every 2% so a large clone does not flood the event bus
            val step = maxOf(1, total / 50)
            if (total == 0 || done / step != before / step || done == total) onProgress(done, total, title)
        }

        override fun endTask() {}

        override fun isCancelled(): Boolean = !job.isActive

        override fun showDuration(enabled: Boolean) {}
    }

    private companion object {
        val URL_PATTERN = Regex("""https://(?:www\.)?github\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?""")
    }
}
