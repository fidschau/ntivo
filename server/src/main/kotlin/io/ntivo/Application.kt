package io.ntivo

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.sse.*
import io.ntivo.agent.configureAskRouting
import io.ntivo.sources.configureSourceRouting
import io.ntivo.sources.createIndexJob

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
        allowMethod(HttpMethod.Delete)
    }
    install(SSE)

    val ctx = AppContext.fromEnvironment()
    val indexJob = createIndexJob(ctx)
    monitor.subscribe(ApplicationStopped) { ctx.close() }

    configureRouting()
    configureApiRouting(ctx)
    configureEventRouting(ctx)
    configureSourceRouting(ctx, indexJob)
    configureAskRouting(ctx)
    // Last, so explicit routes take priority over static files
    configureStaticContent()
}
