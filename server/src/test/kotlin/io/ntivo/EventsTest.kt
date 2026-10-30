package io.ntivo

import io.ntivo.model.Source
import io.ntivo.model.SourceKind
import io.ntivo.model.SourceState
import io.ntivo.model.StageName
import io.ktor.client.plugins.sse.sse
import io.ktor.server.application.install
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ntivo.sources.SourceStore
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import io.ktor.client.plugins.sse.SSE as ClientSSE

class EventsTest {

    @Test
    fun `emit delivers to a collector`() = runTest {
        val bus = EventBus()
        val received = async(start = CoroutineStart.UNDISPATCHED) { bus.flow.first() }
        bus.emit("source.updated", Source("abc", "android-app", SourceKind.github, SourceState.indexing))
        val event = received.await()
        assertEquals("source.updated", event.type)
        assertEquals("android-app", event.data.jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals("indexing", event.data.jsonObject["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun `emit without collectors does not suspend or fail`() = runTest {
        val bus = EventBus()
        repeat(1000) { bus.emit("job.progress", JobProgress("s1", StageName.embed, it, 1000, "Capturing meaning")) }
    }

    @Test
    fun `sse route forwards bus events with their type`() = testApplication {
        val dir = Files.createTempDirectory("events").toFile()
        val ctx = AppContext(
            sources = SourceStore(dir.resolve("sources.json")),
            events = EventBus(),
            vectorStore = VectorStore(),
            codeGraph = CodeGraph(),
            embedder = null,
            executor = null,
            dataDir = dir
        )
        application {
            install(SSE)
            configureEventRouting(ctx)
        }
        val client = createClient { install(ClientSSE) }
        client.sse("/api/events") {
            // The subscription starts just after the first send, so keep emitting until one arrives
            val emitter = launch {
                while (isActive) {
                    ctx.events.emit(EventTypes.JOB_PROGRESS, JobProgress("s1", StageName.split, 3, 10, "Split into 3 pieces"))
                    delay(20)
                }
            }
            val event = incoming.first { it.event == EventTypes.JOB_PROGRESS }
            emitter.cancel()
            val progress = Json.decodeFromString(JobProgress.serializer(), event.data!!)
            assertEquals(StageName.split, progress.stage)
            assertEquals(3, progress.done)
        }
        ctx.close()
        dir.deleteRecursively()
    }
}
