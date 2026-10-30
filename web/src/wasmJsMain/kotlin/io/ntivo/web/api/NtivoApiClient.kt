package io.ntivo.web.api

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ntivo.shared.*

object NtivoApiClient {

    private val client = HttpClient {
        install(ContentNegotiation) {
            json()
        }
    }

    // Use relative URLs — works with webpack proxy in dev and same-origin in prod
    private const val BASE_URL = ""

    suspend fun checkHealth(): HealthResponse {
        return client.get("$BASE_URL/health").body()
    }

    suspend fun search(query: String, collection: String = DEFAULT_COLLECTION, limit: Int = 5): SearchResponse {
        return client.post("$BASE_URL/api/search") {
            contentType(ContentType.Application.Json)
            setBody(SearchRequest(query = query, collection = collection, limit = limit))
        }.body()
    }

    suspend fun parse(code: String): ParseResponse {
        return client.post("$BASE_URL/api/parse") {
            contentType(ContentType.Application.Json)
            setBody(ParseRequest(code = code))
        }.body()
    }

    suspend fun agentChat(prompt: String, collection: String = DEFAULT_COLLECTION): AgentChatResponse {
        return client.post("$BASE_URL/api/agent-chat") {
            contentType(ContentType.Application.Json)
            setBody(AgentChatRequest(prompt = prompt, collection = collection))
        }.body()
    }

    suspend fun ragPipeline(code: String, collection: String = DEFAULT_COLLECTION): RagPipelineResponse {
        return client.post("$BASE_URL/api/rag-pipeline") {
            contentType(ContentType.Application.Json)
            setBody(RagPipelineRequest(code = code, collection = collection))
        }.body()
    }

    suspend fun indexGraph(path: String): GraphIndexResponse {
        return client.post("$BASE_URL/api/graph/index") {
            contentType(ContentType.Application.Json)
            setBody(GraphIndexRequest(path = path))
        }.body()
    }
}
