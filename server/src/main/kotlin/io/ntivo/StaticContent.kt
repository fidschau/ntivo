package io.ntivo

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

// Serves the Compose for Web production build at /.
// Call this AFTER configureRouting() and configureApiRouting()
// so explicit routes (/health, /api/*) take priority.
fun Application.configureStaticContent() {
    routing {
        staticResources("/", "static/compose", index = "index.html")
    }
}
