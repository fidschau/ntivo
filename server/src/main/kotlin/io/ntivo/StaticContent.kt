package io.ntivo

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*

// Serves the Compose for Web production build at / and legacy HTML console at /legacy.
// Call this AFTER configureRouting() and configureApiRouting()
// so explicit routes (/health, /api/*) take priority.
fun Application.configureStaticContent() {
    routing {
        // Compose for Web production build (copied by copyWebDist task)
        staticResources("/", "static/compose", index = "index.html")
        // Legacy HTML dev console as fallback
        staticResources("/legacy", "static", index = "index.html")
    }
}
