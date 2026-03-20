package com.airstrings.sdk.networking

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

internal sealed class FetchResult {
    internal data class Success(val data: ByteArray, val etag: String?) : FetchResult()
    internal data object NotModified : FetchResult()
}

/**
 * Fetches signed string bundles from the CDN via OkHttp.
 *
 * Uses synchronous calls intended to be called from `Dispatchers.IO`.
 * Supports ETag-based conditional requests (If-None-Match / 304 Not Modified).
 */
internal class BundleFetcher {

    private val baseUrl: String = "https://cdn.airstrings.com"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun fetch(
        projectId: String,
        locale: String,
        ifNoneMatch: String? = null,
    ): FetchResult {
        val url = "${baseUrl.trimEnd('/')}/v1/$projectId/$locale/bundle.json"

        val requestBuilder = Request.Builder().url(url).get()

        if (ifNoneMatch != null) {
            requestBuilder.header("If-None-Match", ifNoneMatch)
        }

        val response = client.newCall(requestBuilder.build()).execute()

        response.use { resp ->
            if (resp.code == 304) {
                return FetchResult.NotModified
            }

            if (!resp.isSuccessful) {
                throw RuntimeException("HTTP ${resp.code}")
            }

            val data = resp.body?.bytes()
                ?: throw RuntimeException("Empty response body")

            val etag = resp.header("ETag")
            return FetchResult.Success(data = data, etag = etag)
        }
    }
}
