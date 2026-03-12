package io.ntivo

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*

fun main(args: Array<String>) {
    io.ktor.server.netty.EngineMain.main(args)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }
    install(CORS) {
        allowHost("localhost:8081")  // Compose for Web webpack dev server
        allowHeader(HttpHeaders.ContentType)
        allowMethod(HttpMethod.Post)
    }
    configureRouting()
    configureApiRouting()
    configureStaticContent()
}
