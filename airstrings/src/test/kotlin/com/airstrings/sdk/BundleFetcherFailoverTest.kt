package com.airstrings.sdk

import com.airstrings.sdk.networking.BundleFetcher
import com.airstrings.sdk.networking.FetchResult
import com.airstrings.sdk.networking.HttpStatusException
import okhttp3.OkHttpClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal class BundleFetcherFailoverTest {

    private val servers = mutableListOf<LocalHttpServer>()
    private val cdnBody = "cdn-bundle".toByteArray()
    private val fallbackBody = "fallback-bundle".toByteArray()

    @AfterEach
    fun tearDown() {
        servers.forEach { it.close() }
    }

    private fun track(server: LocalHttpServer): LocalHttpServer = server.also { servers.add(it) }

    private fun makeFetcher(
        cdn: LocalHttpServer,
        fallback: LocalHttpServer?,
        firstAttemptTimeoutMs: Long = 300,
    ): BundleFetcher = BundleFetcher(
        baseUrl = cdn.baseUrl,
        fallbackBaseUrl = fallback?.baseUrl,
        firstAttemptTimeoutMs = firstAttemptTimeoutMs,
    )

    private fun BundleFetcher.fetchBundle(ifNoneMatch: String? = null): FetchResult =
        fetch(
            organizationId = "org_1",
            projectId = "proj_1",
            environmentId = "env_1",
            locale = "en",
            ifNoneMatch = ifNoneMatch,
        )

    private fun assertBody(expected: ByteArray, result: FetchResult) {
        assertContentEquals(expected, assertIs<FetchResult.Success>(result).data)
    }

    @Test
    @DisplayName("CDN success never calls the fallback")
    fun cdnSuccessNeverCallsFallback() {
        val cdn = track(LocalHttpServer.respond(200, cdnBody, mapOf("ETag" to "\"v1\"")))
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))

        val result = makeFetcher(cdn, fallback).fetchBundle()

        assertBody(cdnBody, result)
        assertEquals("\"v1\"", (result as FetchResult.Success).etag)
        assertEquals(1, cdn.hits.get())
        assertEquals(0, fallback.hits.get())
    }

    @Test
    @DisplayName("Hanging CDN fails over after the first-attempt deadline")
    fun cdnHangFailsOverAfterDeadline() {
        val cdn = track(LocalHttpServer.hang())
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))

        val start = System.nanoTime()
        val result = makeFetcher(cdn, fallback, firstAttemptTimeoutMs = 300).fetchBundle()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertBody(fallbackBody, result)
        assertTrue(elapsedMs >= 300, "elapsed $elapsedMs ms")
        assertTrue(elapsedMs < 3_000, "elapsed $elapsedMs ms")
        assertEquals(1, fallback.hits.get())
    }

    @Test
    @DisplayName("Headers within the deadline with a slow body do not fail over")
    fun headersInTimeSlowBodyDoesNotFailOver() {
        val cdn = track(LocalHttpServer.respond(200, cdnBody, bodyDelayMs = 1_000))
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))

        val start = System.nanoTime()
        val result = makeFetcher(cdn, fallback, firstAttemptTimeoutMs = 300).fetchBundle()
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

        assertBody(cdnBody, result)
        assertTrue(elapsedMs >= 1_000, "elapsed $elapsedMs ms")
        assertEquals(0, fallback.hits.get())
    }

    @Test
    @DisplayName("CDN dropping the body mid-read fails over")
    fun cdnBodyDropFailsOver() {
        val cdn = track(LocalHttpServer.dropBody())
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))

        val result = makeFetcher(cdn, fallback).fetchBundle()

        assertBody(fallbackBody, result)
        assertEquals(1, cdn.hits.get())
        assertEquals(1, fallback.hits.get())
    }

    @Test
    @DisplayName("Refused CDN connection fails over")
    fun cdnRefusedFailsOver() {
        val cdn = track(LocalHttpServer.refused())
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))

        val result = makeFetcher(cdn, fallback).fetchBundle()

        assertBody(fallbackBody, result)
        assertEquals(1, fallback.hits.get())
    }

    @Test
    @DisplayName("CDN 5xx fails over")
    fun cdn5xxFailsOver() {
        val cdn = track(LocalHttpServer.respond(503))
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))

        val result = makeFetcher(cdn, fallback).fetchBundle()

        assertBody(fallbackBody, result)
        assertEquals(1, cdn.hits.get())
        assertEquals(1, fallback.hits.get())
    }

    @Test
    @DisplayName("CDN 404 does not fail over")
    fun cdn404DoesNotFailOver() {
        val cdn = track(LocalHttpServer.respond(404))
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))

        val error = assertFailsWith<HttpStatusException> { makeFetcher(cdn, fallback).fetchBundle() }

        assertEquals(404, error.code)
        assertEquals(0, fallback.hits.get())
    }

    @Test
    @DisplayName("CDN 304 does not fail over")
    fun cdn304DoesNotFailOver() {
        val cdn = track(LocalHttpServer.respond(304))
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))

        val result = makeFetcher(cdn, fallback).fetchBundle(ifNoneMatch = "\"v1\"")

        assertEquals(FetchResult.NotModified, result)
        assertEquals(0, fallback.hits.get())
    }

    @Test
    @DisplayName("Without a fallback the first-attempt deadline is not applied")
    fun noFallbackIgnoresFirstAttemptDeadline() {
        val cdn = track(LocalHttpServer.hang())
        val client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build()
        val fetcher = BundleFetcher(
            baseUrl = cdn.baseUrl,
            client = client,
            firstAttemptTimeoutMs = 300,
        )

        val pending = CompletableFuture.supplyAsync { runCatching { fetcher.fetchBundle() } }
        Thread.sleep(1_000)

        assertFalse(pending.isDone)
        assertIs<IOException>(pending.get(10, TimeUnit.SECONDS).exceptionOrNull())
    }

    @Test
    @DisplayName("Both hosts failing throws")
    fun bothHostsFailThrows() {
        val cdn = track(LocalHttpServer.respond(503))
        val fallback = track(LocalHttpServer.respond(500))

        val error = assertFailsWith<HttpStatusException> { makeFetcher(cdn, fallback).fetchBundle() }

        assertEquals(500, error.code)
        assertEquals(1, cdn.hits.get())
        assertEquals(1, fallback.hits.get())
    }

    @Test
    @DisplayName("Fallback stays first after a successful failover")
    fun stickyAfterFailover() {
        val cdn = track(LocalHttpServer.respond(503))
        val fallback = track(LocalHttpServer.respond(200, fallbackBody))
        val fetcher = makeFetcher(cdn, fallback)

        assertBody(fallbackBody, fetcher.fetchBundle())
        assertBody(fallbackBody, fetcher.fetchBundle())

        assertEquals(1, cdn.hits.get())
        assertEquals(2, fallback.hits.get())
    }

    @Test
    @DisplayName("If-None-Match is sent to the fallback and its 304 is handled")
    fun ifNoneMatchSentToFallbackAnd304Handled() {
        val cdn = track(LocalHttpServer.respond(502))
        val fallback = track(LocalHttpServer.respond(304))

        val result = makeFetcher(cdn, fallback).fetchBundle(ifNoneMatch = "\"v7\"")

        assertEquals(FetchResult.NotModified, result)
        assertTrue(cdn.requestHeads.single().contains("If-None-Match: \"v7\"\r\n"))
        assertTrue(fallback.requestHeads.single().contains("If-None-Match: \"v7\"\r\n"))
    }
}
