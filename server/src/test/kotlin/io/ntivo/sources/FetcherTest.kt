package io.ntivo.sources

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetcherTest {

    @Test
    fun `GitHub URLs are parsed into owner and repo`() {
        assertEquals("android/nowinandroid", GitHubFetcher("https://github.com/android/nowinandroid", null, null).name)
        assertEquals("android/nowinandroid", GitHubFetcher("https://github.com/android/nowinandroid.git", null, null).name)
        assertEquals("sportcity/ios-app", GitHubFetcher("https://github.com/sportcity/ios-app/", "main", "t").name)
    }

    @Test
    fun `non-GitHub URLs are rejected`() {
        listOf(
            "https://gitlab.com/android/nowinandroid",
            "git@github.com:android/nowinandroid.git",
            "https://github.com/android",
            "file:///etc/passwd",
            "https://github.com.evil.io/a/b"
        ).forEach { url ->
            val e = assertFailsWith<IllegalArgumentException>(url) { GitHubFetcher(url, null, null) }
            assertEquals("Only github.com URLs are supported", e.message)
        }
    }

    @Test
    fun `the token never appears in the fetcher's string form`() {
        assertFalse("ghp_secret" in GitHubFetcher("https://github.com/a/b", null, "ghp_secret").toString())
    }

    @Test
    fun `upload writes files and returns only supported ones outside skipped folders`(): Unit = runBlocking {
        val into = Files.createTempDirectory("upload").toFile()
        val fetcher = UploadFetcher(
            "android-app",
            listOf(
                UploadedFile("app/src/Repo.kt", "fun load() = 1".toByteArray()),
                UploadedFile("ios/InviteService.swift", "func go() {}".toByteArray()),
                UploadedFile("app/build/x.kt", "fun gen() = 2".toByteArray()),
                UploadedFile("README.md", "# hi".toByteArray()),
                UploadedFile("ios/Pods/Lib/Lib.swift", "func lib() {}".toByteArray())
            )
        )
        val progress = mutableListOf<Pair<Int, Int>>()
        val result = fetcher.fetch(into) { done, total, _ -> progress += done to total }

        assertEquals("android-app", result.name)
        assertEquals(into, result.dir)
        assertEquals(
            listOf("app/src/Repo.kt", "ios/InviteService.swift"),
            result.files.map { it.relativeTo(into).path }.sorted()
        )
        assertEquals("fun load() = 1", File(into, "app/src/Repo.kt").readText())
        assertTrue(progress.isNotEmpty())
        assertEquals(progress.last().first, progress.last().second)
        into.deleteRecursively()
    }

    @Test
    fun `upload rejects paths that escape the working directory`() {
        listOf("../evil.kt", "app/../../evil.kt", "/etc/evil.kt").forEach { path ->
            assertFailsWith<IllegalArgumentException>(path) {
                UploadFetcher("x", listOf(UploadedFile(path, ByteArray(0))))
            }
        }
    }

    @Test
    fun `supported files skip build, git, Pods and node_modules folders`() {
        val root = Files.createTempDirectory("walk").toFile()
        listOf("a/A.kt", "a/B.swift", "build/C.kt", ".git/D.kt", "x/Pods/E.swift", "node_modules/F.kt", "a/G.java")
            .forEach { File(root, it).apply { parentFile.mkdirs(); writeText("") } }
        assertEquals(listOf("a/A.kt", "a/B.swift"), supportedFiles(root).map { it.relativeTo(root).path }.sorted())
        root.deleteRecursively()
    }
}
