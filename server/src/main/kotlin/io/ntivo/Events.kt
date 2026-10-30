package io.ntivo

import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.sse.*
import io.ktor.sse.*
import io.ntivo.model.StageName
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.time.Duration.Companion.seconds

/** One server-sent event: [type] becomes the SSE `event:` line, [data] its JSON `data:` line. */
@Serializable
data class Event(val type: String, val data: JsonElement)

/** `job.progress` payload. */
@Serializable
data class JobProgress(
    val sourceId: String,
    val stage: StageName,
    val done: Int,
    val total: Int,
    val message: String
)

/** `answer.activity` payload. */
@Serializable
data class AnswerActivity(val answerId: String, val message: String)

/** Event type names used on the bus. */
object EventTypes {
    const val SOURCE_UPDATED = "source.updated"
    const val JOB_PROGRESS = "job.progress"
    const val ANSWER_ACTIVITY = "answer.activity"
}

/**
 * In-process fan-out of events to every connected SSE client.
 *
 * Emitting never suspends: with no subscribers events are dropped, and a slow
 * subscriber loses the oldest buffered events rather than stalling an index job.
 */
class EventBus {
    private val _flow = MutableSharedFlow<Event>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val flow: SharedFlow<Event> = _flow.asSharedFlow()

    fun emit(event: Event) {
        _flow.tryEmit(event)
    }

    inline fun <reified T> emit(type: String, data: T) =
        emit(Event(type, eventJson.encodeToJsonElement(data)))

    companion object {
        val eventJson = Json { encodeDefaults = true }
    }
}

/** `GET /api/events`: streams every [Event] on the bus as a server-sent event. */
fun Application.configureEventRouting(ctx: AppContext) {
    routing {
        sse("/api/events") {
            // A comment line flushes the response headers so EventSource fires `open` at once,
            // and the heartbeat keeps idle connections from being closed.
            heartbeat {
                period = 15.seconds
                event = ServerSentEvent(comments = "heartbeat")
            }
            send(ServerSentEvent(comments = "connected"))
            ctx.events.flow.collect { event ->
                send(
                    ServerSentEvent(
                        data = EventBus.eventJson.encodeToString(JsonElement.serializer(), event.data),
                        event = event.type
                    )
                )
            }
        }
    }
}
