package io.ntivo

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticContentTest {

    @Test
    fun `root serves the static app`() = testApplication {
        application { configureStaticContent() }
        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("app.js" in body, "expected the static app index.html, got: ${body.take(200)}")
        assertTrue("ntivo-web.js" !in body, "the Compose page must no longer be served at /")
    }

    @Test
    fun `app assets are served next to the page`() = testApplication {
        application { configureStaticContent() }
        for (path in listOf("/app.css", "/app.js", "/api.js", "/mock.js", "/components/chat.js")) {
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status, path)
        }
        assertEquals(
            ContentType.Text.JavaScript.contentType,
            client.get("/app.js").contentType()?.contentType,
            "ES modules must be served with a JavaScript content type"
        )
    }

    @Test
    fun `under the hood without a trailing slash redirects so relative assets resolve`() = testApplication {
        application { configureStaticContent() }
        val noRedirects = createClient { followRedirects = false }
        val response = noRedirects.get("/under-the-hood")
        assertTrue(response.status.value in 300..399, "expected a redirect, got ${response.status}")
        assertEquals("/under-the-hood/", response.headers[HttpHeaders.Location])
    }

    @Test
    fun `under the hood serves the Compose build when it has been built`() = testApplication {
        // The Compose build is copied into build/resources by copyWebDist, which tests skip.
        assumeTrue(
            "Compose build not present; run ./gradlew :server:processResources first",
            javaClass.classLoader.getResource("static/compose/index.html") != null
        )
        application { configureStaticContent() }
        val body = client.get("/under-the-hood/").bodyAsText()
        assertTrue("ntivo-web.js" in body)
    }
}
