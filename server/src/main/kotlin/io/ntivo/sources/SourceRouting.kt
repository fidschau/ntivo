package io.ntivo.sources

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import io.ntivo.AppContext
import io.ntivo.model.Source
import io.ntivo.model.SourceKind
import io.ntivo.model.SourceState
import io.ntivo.model.StageState
import io.ntivo.parse.Language
import io.ntivo.shared.ErrorResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File

/** `POST /api/sources/github` body. [token] is kept in memory for the job only. */
@Serializable
data class GitHubConnectRequest(val url: String, val branch: String? = null, val token: String? = null) {
    override fun toString() =
        "GitHubConnectRequest(url=$url, branch=$branch, token=${if (token.isNullOrBlank()) "none" else "***"})"
}

/** Error set on sources that were still indexing when the server stopped. */
const val INTERRUPTED_BY_RESTART = "Interrupted by restart"

/** Largest single uploaded file accepted. Bigger files are generated code and skipped at split anyway. */
private const val MAX_UPLOAD_PART_BYTES = 20L * 1024 * 1024

private val log = LoggerFactory.getLogger("io.ntivo.sources.SourceRouting")

/**
 * Source endpoints:
 * ```
 * GET    /api/sources          -> List<Source>
 * POST   /api/sources/github   {url, branch?, token?} -> Source (202), 400 on a non-GitHub URL
 * POST   /api/sources/upload   multipart: name; files with filename = relative path -> Source (202)
 * DELETE /api/sources/{id}     -> 204, 404 if unknown
 * ```
 * [fetcherFor] turns a validated [GitHubFetcher] into the fetcher the job runs. It exists so
 * tests can connect a GitHub URL without cloning; the server uses the fetcher as is.
 *
 * Also marks sources left in `indexing` by a previous run as failed ([INTERRUPTED_BY_RESTART]).
 */
fun Application.configureSourceRouting(
    ctx: AppContext,
    indexJob: IndexJob,
    fetcherFor: (GitHubFetcher) -> Fetcher = { it }
) {
    markInterrupted(ctx.sources)

    routing {
        route("/api/sources") {

            get {
                call.respond(ctx.sources.all())
            }

            post("/github") {
                val request = runCatching { call.receive<GitHubConnectRequest>() }.getOrElse {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Send a JSON body with a 'url'."))
                }
                val fetcher = try {
                    GitHubFetcher(request.url, request.branch?.trim()?.takeIf { it.isNotEmpty() }, request.token)
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid URL"))
                }
                val source = Source(newSourceId(), fetcher.name, SourceKind.github, SourceState.indexing)
                indexJob.start(source, fetcherFor(fetcher))
                log.info("Connecting {} as source {}", fetcher.name, source.id)
                call.respond(HttpStatusCode.Accepted, ctx.sources.get(source.id) ?: source)
            }

            post("/upload") {
                val upload = try {
                    receiveUpload(call)
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid upload"))
                }
                if (upload.fileParts == 0) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Choose a folder with at least one file."))
                }
                val name = upload.name?.trim()?.takeIf { it.isNotEmpty() }
                    ?: upload.files.firstOrNull()?.relativePath?.substringBefore('/')?.takeIf { it.isNotEmpty() }
                    ?: "upload"
                val fetcher = try {
                    UploadFetcher(name, upload.files)
                } catch (e: IllegalArgumentException) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid upload"))
                }
                val source = Source(newSourceId(), name, SourceKind.upload, SourceState.indexing)
                indexJob.start(source, fetcher)
                log.info("Indexing upload {} as source {}: {} of {} files kept", name, source.id, upload.files.size, upload.fileParts)
                call.respond(HttpStatusCode.Accepted, ctx.sources.get(source.id) ?: source)
            }

            delete("/{id}") {
                val id = call.parameters["id"].orEmpty()
                if (ctx.sources.get(id) == null) {
                    return@delete call.respond(HttpStatusCode.NotFound, ErrorResponse("Unknown source '$id'"))
                }
                deleteSource(ctx, indexJob, id)
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/** Sets every source still `indexing` (the server stopped mid-job) to `failed`, failing its running stage. */
internal fun markInterrupted(store: SourceStore) {
    store.all().filter { it.state == SourceState.indexing }.forEach { source ->
        store.update(source.id) { s ->
            s.copy(
                state = SourceState.failed,
                error = INTERRUPTED_BY_RESTART,
                stages = s.stages.map { if (it.state == StageState.running) it.copy(state = StageState.failed) else it }
            )
        }
        log.info("Source {} ({}) was indexing when the server stopped; marked failed", source.id, source.name)
    }
}

/**
 * Removes a source everywhere: stops its job, deletes its pieces and graph nodes, its
 * working copy, and finally the store entry. Qdrant and Neo4j being down does not block
 * the delete; their leftovers carry a source id no longer listed, so they are never searched.
 */
private suspend fun deleteSource(ctx: AppContext, indexJob: IndexJob, id: String) {
    indexJob.cancel(id)
    runCatching { withTimeout(15_000) { ctx.vectorStore.deleteBySource(ctx.collection, id) } }
        .onFailure { log.warn("Could not delete pieces of source {}: {}", id, it.message) }
    withContext(Dispatchers.IO) {
        runCatching { if (ctx.codeGraph.isReachable()) ctx.codeGraph.deleteSource(id) }
            .onFailure { log.warn("Could not delete graph of source {}: {}", id, it.message) }
        File(ctx.reposDir, id).deleteRecursively()
    }
    ctx.sources.remove(id)
    log.info("Deleted source {}", id)
}

private class Upload(val name: String?, val files: List<UploadedFile>, val fileParts: Int)

/**
 * Reads the multipart body. Every file part counts towards [Upload.fileParts], but only
 * files in a supported language are kept in memory: a folder upload can be large and the
 * rest would be ignored at split anyway.
 */
private suspend fun receiveUpload(call: ApplicationCall): Upload {
    var name: String? = null
    var fileParts = 0
    val files = mutableListOf<UploadedFile>()
    call.receiveMultipart(formFieldLimit = MAX_UPLOAD_PART_BYTES).forEachPart { part ->
        try {
            when (part) {
                is PartData.FormItem -> if (part.name == "name") name = part.value
                is PartData.FileItem -> {
                    fileParts++
                    val path = part.originalFileName?.takeIf { it.isNotBlank() }
                        ?: throw IllegalArgumentException("Each file needs its relative path as the filename")
                    if (Language.forFile(path) != null) {
                        files += UploadedFile(path, part.provider().toByteArray())
                    } else if (path.replace('\\', '/').split('/').any { it == ".." }) {
                        // Reject unsafe paths even for files we would not keep
                        throw IllegalArgumentException("Invalid path: $path")
                    }
                }
                else -> Unit
            }
        } finally {
            part.dispose()
        }
    }
    return Upload(name, files, fileParts)
}
