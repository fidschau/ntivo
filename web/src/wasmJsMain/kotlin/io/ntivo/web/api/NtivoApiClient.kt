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

    suspend fun chat(prompt: String): ChatResponse {
        return client.post("$BASE_URL/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(prompt = prompt))
        }.body()
    }

    suspend fun embed(text: String, taskType: String = "RETRIEVAL_DOCUMENT"): EmbedResponse {
        return client.post("$BASE_URL/api/embed") {
            contentType(ContentType.Application.Json)
            setBody(EmbedRequest(text = text, taskType = taskType))
        }.body()
    }

    suspend fun store(text: String, label: String = "", collection: String = "chunks_demo"): StoreResponse {
        return client.post("$BASE_URL/api/store") {
            contentType(ContentType.Application.Json)
            setBody(StoreRequest(text = text, label = label, collection = collection))
        }.body()
    }

    suspend fun search(query: String, collection: String = "chunks_demo", limit: Int = 5): SearchResponse {
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
}
