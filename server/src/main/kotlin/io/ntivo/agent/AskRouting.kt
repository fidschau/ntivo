package io.ntivo.agent

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ntivo.AppContext
import io.ntivo.model.Piece
import io.ntivo.shared.ErrorResponse
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class AskRequest(val question: String, val sourceIds: List<String>? = null)

private const val MISSING_KEY = "NTIVO_GEMINI_API_KEY not set. Export it and restart the server."

private val log = LoggerFactory.getLogger("io.ntivo.agent.AskRouting")

/**
 * `POST /api/ask`, `GET /api/answers/{id}/trace` and `GET /api/pieces/{id}`.
 * Answering needs a model key; without one `/api/ask` responds 503.
 */
fun Application.configureAskRouting(ctx: AppContext) {
    val answerer = if (ctx.executor != null && ctx.embedder != null) AnswerService(ctx) else null
    configureAskRouting(ctx, answerer) { id -> ctx.vectorStore.getPiece(ctx.collection, id) }
}

/** As above, with the answerer and piece lookup injected. A null [answerer] means no model key. */
fun Application.configureAskRouting(
    ctx: AppContext,
    answerer: QuestionAnswerer?,
    pieceLookup: suspend (String) -> Piece?
) {
    val answers = AnswerStore(ctx.tracesDir)

    routing {
        route("/api") {
            post("/ask") {
                val request = runCatching { call.receive<AskRequest>() }.getOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("Send {\"question\": \"...\"}."))
                if (request.question.isBlank()) {
                    return@post call.respond(HttpStatusCode.BadRequest, ErrorResponse("'question' cannot be blank."))
                }
                if (answerer == null) {
                    return@post call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse(MISSING_KEY))
                }
                val answer = try {
                    answerer.ask(request.question.trim(), request.sourceIds)
                } catch (e: Exception) {
                    log.warn("Answering failed: {}", safeMessage(e))
                    return@post call.respond(HttpStatusCode.BadGateway, ErrorResponse("Could not answer: ${safeMessage(e)}"))
                }
                call.respond(answer)
            }

            get("/answers/{id}/trace") {
                val trace = answers.trace(call.parameters["id"].orEmpty())
                    ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("No trace for this answer."))
                call.respond(trace)
            }

            get("/pieces/{id}") {
                val id = call.parameters["id"].orEmpty()
                val piece = try {
                    pieceLookup(id)
                } catch (e: Exception) {
                    // A missing collection means nothing was indexed yet; anything else is Qdrant being down
                    val indexed = runCatching { ctx.vectorStore.collectionExists(ctx.collection) }.getOrNull()
                    if (indexed == false) null
                    else return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ErrorResponse("Code store unavailable. Is Qdrant running? docker compose up -d")
                    )
                }
                if (piece == null) {
                    return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("No piece with this id."))
                }
                call.respond(piece)
            }
        }
    }
}
