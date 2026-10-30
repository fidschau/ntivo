package io.ntivo

import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

/** Path of the old Compose for Web developer console. Must end with a slash: its assets are relative. */
const val UNDER_THE_HOOD_PATH = "/under-the-hood/"

// Serves the static app (plain HTML, CSS and ES modules) at / and the Compose for Web
// production build at /under-the-hood/.
// Call this AFTER every other configureX() so explicit routes (/health, /api/*) take priority.
fun Application.configureStaticContent() {
    routing {
        // The Compose index.html loads ntivo-web.js and its .wasm files by relative URL,
        // so it only works when the page URL ends with a slash.
        get(UNDER_THE_HOOD_PATH.trimEnd('/')) {
            call.respondRedirect(UNDER_THE_HOOD_PATH)
        }
        staticResources(UNDER_THE_HOOD_PATH.trimEnd('/'), "static/compose", index = "index.html")
        staticResources("/", "static/app", index = "index.html")
    }
}
