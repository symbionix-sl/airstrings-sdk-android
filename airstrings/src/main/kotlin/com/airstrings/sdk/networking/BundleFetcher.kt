package com.airstrings.sdk.networking

import android.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Timer
import java.util.concurrent.TimeUnit
import kotlin.concurrent.schedule

internal sealed class FetchResult {
    internal data class Success(val data: ByteArray, val etag: String?) : FetchResult()
    internal data object NotModified : FetchResult()
}

internal class HttpStatusException(val code: Int) : RuntimeException("HTTP $code")

/**
 * Fetches signed string bundles from the CDN via OkHttp.
 *
 * Uses synchronous calls intended to be called from `Dispatchers.IO`.
 * Supports ETag-based conditional requests (If-None-Match / 304 Not Modified).
 */
internal open class BundleFetcher(
    baseUrl: String,
    fallbackBaseUrl: String? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val firstAttemptTimeoutMs: Long = 5_000,
) {

    @Volatile
    private var hosts: List<String> = listOfNotNull(baseUrl, fallbackBaseUrl)

    open fun fetch(
        organizationId: String,
        projectId: String,
        environmentId: String,
        locale: String,
        ifNoneMatch: String? = null,
    ): FetchResult {
        val path = "$organizationId/$projectId/$environmentId/$locale/bundle.json"
        val order = hosts

        if (order.size == 1) {
            return attempt(order[0], path, ifNoneMatch, deadlineMs = null)
        }

        try {
            return attempt(order[0], path, ifNoneMatch, deadlineMs = firstAttemptTimeoutMs)
        } catch (e: Exception) {
            if (e !is IOException && !(e is HttpStatusException && e.code >= 500)) throw e
        }

        Log.w(TAG, "Fetch failed on ${hostOf(order[0])}, retrying on ${hostOf(order[1])}")
        val result = attempt(order[1], path, ifNoneMatch, deadlineMs = null)
        hosts = order.reversed()
        return result
    }

    private fun attempt(base: String, path: String, ifNoneMatch: String?, deadlineMs: Long?): FetchResult {
        val requestBuilder = Request.Builder().url("${base.trimEnd('/')}/$path").get()

        if (ifNoneMatch != null) {
            requestBuilder.header("If-None-Match", ifNoneMatch)
        }

        val call = client.newCall(requestBuilder.build())
        val deadline = deadlineMs?.let { deadlineTimer.schedule(it) { call.cancel() } }
        val response = try {
            call.execute()
        } finally {
            deadline?.cancel()
        }

        response.use { resp ->
            if (resp.code == 304) {
                return FetchResult.NotModified
            }

            if (!resp.isSuccessful) {
                throw HttpStatusException(resp.code)
            }

            val data = resp.body?.bytes()
                ?: throw RuntimeException("Empty response body")

            val etag = resp.header("ETag")
            return FetchResult.Success(data = data, etag = etag)
        }
    }

    private fun hostOf(base: String): String = base.toHttpUrlOrNull()?.host ?: base

    private companion object {
        private const val TAG = "AirStrings"
        private val deadlineTimer by lazy { Timer("AirStrings-fetch-deadline", true) }
    }
}
