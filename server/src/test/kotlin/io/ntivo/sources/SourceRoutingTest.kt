package io.ntivo.sources

import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ntivo.AppContext
import io.ntivo.CodeGraph
import io.ntivo.EventBus
import io.ntivo.VectorStore
import io.ntivo.model.Piece
import io.ntivo.model.Source
import io.ntivo.model.SourceKind
import io.ntivo.model.SourceState
import io.ntivo.model.Stage
import io.ntivo.model.StageName
import io.ntivo.model.StageState
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceRoutingTest {

    private val dataDir = Files.createTempDirectory("sourcerouting").toFile()

    // A throwaway org, so deletes touch a collection that does not exist rather than the dev one
    private val ctx = AppContext(
        sources = SourceStore(File(dataDir, "sources.json")),
        events = EventBus(),
        vectorStore = VectorStore(),
        codeGraph = CodeGraph(),
        embedder = null,
        executor = null,
        dataDir = dataDir,
        orgId = "test_${UUID.randomUUID().toString().take(8)}"
    )
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        ctx.close()
        dataDir.deleteRecursively()
    }

    private val fakePieces = object : PieceIndexer {
        override suspend fun index(sourceId: String, sourceName: String, pieces: List<Piece>, onProgress: (Int, Int) -> Unit) =
            pieces.size.also { onProgress(it, it) }
    }
    private val fakeGraph = object : GraphIndexer {
        override suspend fun rebuild(sourceId: String, sourceName: String, files: List<ParsedSourceFile>, onProgress: (Int, Int) -> Unit) =
            GraphStats(0, files.size, 0, 0)
    }

    /** Stands in for a clone: writes one Kotlin file, or waits until cancelled when [hang] is set. */
    private class FakeClone(private val hang: Boolean = false) : Fetcher {
        override suspend fun fetch(into: File, onProgress: (Int, Int, String) -> Unit): FetchResult {
            if (hang) kotlinx.coroutines.awaitCancellation()
            File(into, "app/Repo.kt").apply { parentFile.mkdirs(); writeText("fun load() = 1\n") }
            return FetchResult(into, supportedFiles(into), "ignored")
        }
    }

    private val clones = mutableListOf<GitHubFetcher>()

    private fun ApplicationTestBuilder.app(hang: Boolean = false) {
        application {
            install(ContentNegotiation) { json() }
            configureSourceRouting(ctx, IndexJob(ctx, fakePieces, fakeGraph)) { real ->
                clones += real
                FakeClone(hang)
            }
        }
    }

    private suspend fun awaitSettled(id: String): Source = withTimeout(10_000) {
        var s = ctx.sources.get(id)
        while (s == null || s.state == SourceState.indexing) {
            delay(20)
            s = ctx.sources.get(id)
        }
        s
    }

    @Test
    fun `connecting a GitHub URL returns 202 and stores the source`() = testApplication {
        app()
        val response = client.post("/api/sources/github") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://github.com/android/nowinandroid","branch":"main","token":"ghp_secret"}""")
        }
        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = response.bodyAsText()
        assertFalse("ghp_secret" in body)
        val source = json.decodeFromString(Source.serializer(), body)
        assertEquals("android/nowinandroid", source.name)
        assertEquals(SourceKind.github, source.kind)
        assertEquals(SourceState.indexing, source.state)
        assertNotNull(ctx.sources.get(source.id))
        assertEquals("android/nowinandroid", clones.single().name)

        assertEquals(SourceState.ready, awaitSettled(source.id).state)
        val listed = json.decodeFromString(ListSerializer(Source.serializer()), client.get("/api/sources").bodyAsText())
        assertEquals(listOf(source.id), listed.map { it.id })
        assertFalse(File(dataDir, "sources.json").readText().contains("ghp_secret"))
    }

    @Test
    fun `a non-GitHub URL is rejected with 400`() = testApplication {
        app()
        val response = client.post("/api/sources/github") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://gitlab.com/a/b"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue("Only github.com URLs are supported" in response.bodyAsText())
        assertTrue(ctx.sources.all().isEmpty())
    }

    @Test
    fun `uploading two files indexes them`() = testApplication {
        app()
        val response = client.submitFormWithBinaryData(
            url = "/api/sources/upload",
            formData = formData {
                append("name", "android-app")
                append("files[]", "fun load() = 1\n".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"android-app/app/Repo.kt\"")
                })
                append("files[]", "func go() {}\n".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"android-app/ios/Go.swift\"")
                })
            }
        )
        assertEquals(HttpStatusCode.Accepted, response.status)
        val source = json.decodeFromString(Source.serializer(), response.bodyAsText())
        assertEquals("android-app", source.name)
        assertEquals(SourceKind.upload, source.kind)

        val settled = awaitSettled(source.id)
        assertEquals(SourceState.ready, settled.state, settled.error)
        assertEquals(2, settled.files)
        assertEquals(listOf("Kotlin", "Swift"), settled.languages)
    }

    @Test
    fun `an upload with no files is rejected with 400`() = testApplication {
        app()
        val response = client.submitFormWithBinaryData(
            url = "/api/sources/upload",
            formData = formData { append("name", "empty") }
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(ctx.sources.all().isEmpty())
    }

    @Test
    fun `an upload with a path escaping the folder is rejected with 400`() = testApplication {
        app()
        val response = client.submitFormWithBinaryData(
            url = "/api/sources/upload",
            formData = formData {
                append("files[]", "fun x() = 1".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"../evil.kt\"")
                })
            }
        )
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(ctx.sources.all().isEmpty())
    }

    @Test
    fun `an upload with only unsupported files fails with a clear error`() = testApplication {
        app()
        val response = client.submitFormWithBinaryData(
            url = "/api/sources/upload",
            formData = formData {
                append("name", "docs")
                append("files[]", "# Docs".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentDisposition, "filename=\"docs/README.md\"")
                })
            }
        )
        assertEquals(HttpStatusCode.Accepted, response.status)
        val source = json.decodeFromString(Source.serializer(), response.bodyAsText())
        val settled = awaitSettled(source.id)
        assertEquals(SourceState.failed, settled.state)
        assertEquals(NO_SUPPORTED_FILES, settled.error)
    }

    @Test
    fun `deleting a source cancels its job and removes it everywhere`() = testApplication {
        app(hang = true)
        val created = client.post("/api/sources/github") {
            contentType(ContentType.Application.Json)
            setBody("""{"url":"https://github.com/android/nowinandroid"}""")
        }
        val source = json.decodeFromString(Source.serializer(), created.bodyAsText())
        File(ctx.reposDir, source.id).mkdirs()

        val response = client.delete("/api/sources/${source.id}")
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertTrue(ctx.sources.all().isEmpty())
        assertFalse(File(ctx.reposDir, source.id).exists())

        assertEquals(HttpStatusCode.NotFound, client.delete("/api/sources/${source.id}").status)
    }

    @Test
    fun `sources left indexing by a restart are marked failed`() = testApplication {
        val running = Source(
            "old", "android-app", SourceKind.github, SourceState.indexing,
            stages = listOf(
                Stage(StageName.fetch, StageState.done), Stage(StageName.split, StageState.done),
                Stage(StageName.embed, StageState.running, 10, 100), Stage(StageName.connect)
            )
        )
        ctx.sources.put(running)
        ctx.sources.put(Source("fine", "ios-app", SourceKind.github, SourceState.ready))
        app()
        client.get("/api/sources") // starts the application

        val s = ctx.sources.get("old")!!
        assertEquals(SourceState.failed, s.state)
        assertEquals("Interrupted by restart", s.error)
        assertEquals(StageState.failed, s.stages[2].state)
        assertEquals(SourceState.ready, ctx.sources.get("fine")!!.state)
        assertNull(ctx.sources.get("fine")!!.error)
    }
}
