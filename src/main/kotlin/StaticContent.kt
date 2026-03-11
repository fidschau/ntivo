package io.ntivo

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

// Serves the static web dev console from /src/main/resources/static/.
// Navigating to / in a browser loads index.html.
// Call this AFTER configureRouting() and configureApiRouting()
// so explicit routes (/health, /api/*) take priority.
fun Application.configureStaticContent() {
    routing {
        staticResources("/", "static", index = "index.html")
    }
}
