package com.airstrings.sdk.storage

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Disk cache for signed string bundles.
 *
 * Cache layout:
 * ```
 * {baseDirectory}/{projectId}/{environmentId}/{locale}/bundle.json
 * {baseDirectory}/{projectId}/{environmentId}/{locale}/metadata.json
 * ```
 *
 * Metadata stores etag and cached timestamp. If metadata is missing or corrupted,
 * load still returns the bundle data with null etag (graceful degradation).
 */
internal class BundleStore(
    private val baseDirectory: File,
) {

    internal data class CacheEntry(
        val data: ByteArray,
        val etag: String?,
    )

    private fun directory(projectId: String, environmentId: String, locale: String): File {
        return File(File(File(baseDirectory, projectId), environmentId), locale)
    }

    fun save(data: ByteArray, projectId: String, environmentId: String, locale: String, etag: String?) {
        val dir = directory(projectId, environmentId, locale)
        try {
            dir.mkdirs()
            File(dir, "bundle.json").writeBytes(data)

            val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            dateFormat.timeZone = TimeZone.getTimeZone("UTC")

            val etagJson = if (etag != null) "\"${escapeJsonString(etag)}\"" else "null"
            val metadata = """{"etag":$etagJson,"cachedAt":"${dateFormat.format(Date())}"}"""
            File(dir, "metadata.json").writeText(metadata)
        } catch (_: Exception) {
            // Logging handled by caller (AirStrings orchestrator)
        }
    }

    fun load(projectId: String, environmentId: String, locale: String): CacheEntry? {
        val dir = directory(projectId, environmentId, locale)
        val bundleFile = File(dir, "bundle.json")

        if (!bundleFile.exists()) return null

        val data = try {
            bundleFile.readBytes()
        } catch (_: Exception) {
            return null
        }

        val etag: String? = try {
            val metadataFile = File(dir, "metadata.json")
            if (metadataFile.exists()) {
                val text = metadataFile.readText()
                parseEtagFromMetadata(text)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

        return CacheEntry(data = data, etag = etag)
    }

    fun delete(projectId: String, environmentId: String, locale: String) {
        val dir = directory(projectId, environmentId, locale)
        try {
            dir.deleteRecursively()
        } catch (_: Exception) {
            // Best effort
        }
    }

    private fun escapeJsonString(s: String): String {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    private fun parseEtagFromMetadata(json: String): String? {
        // Minimal JSON parsing for {"etag":"value",...} or {"etag":null,...}
        val etagIndex = json.indexOf("\"etag\"")
        if (etagIndex == -1) return null

        val colonIndex = json.indexOf(':', etagIndex + 6)
        if (colonIndex == -1) return null

        val afterColon = json.substring(colonIndex + 1).trimStart()
        if (afterColon.startsWith("null")) return null
        if (!afterColon.startsWith("\"")) return null

        // Extract the quoted string value
        val sb = StringBuilder()
        var i = 1 // skip the opening quote
        while (i < afterColon.length) {
            val ch = afterColon[i]
            if (ch == '"') break
            if (ch == '\\' && i + 1 < afterColon.length) {
                sb.append(afterColon[i + 1])
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
