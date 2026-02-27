package com.airstrings.sdk.models

/**
 * Produces the canonical JSON byte string used for Ed25519 signature verification.
 *
 * Canonical JSON rules (matching RFC 8785 / JCS subset):
 * - No whitespace between tokens
 * - Object keys sorted lexicographically by Unicode code point (recursive)
 * - No trailing commas
 * - Integers serialized without `.0`
 * - Strings escaped per RFC 8259 (only `"`, `\`, and control chars U+0000-U+001F)
 * - UTF-8 encoding, no BOM
 */
internal object CanonicalJson {

    /**
     * Builds the signed content from a bundle: format_version, project_id, locale,
     * revision, created_at, and strings — field order matches the backend/contract.
     */
    fun signedContent(bundle: StringBundle): ByteArray {
        val json = StringBuilder()
        json.append('{')
        json.append("\"format_version\":").append(bundle.formatVersion)
        json.append(",\"project_id\":").append(escapeString(bundle.projectId))
        json.append(",\"locale\":").append(escapeString(bundle.locale))
        json.append(",\"revision\":").append(bundle.revision)
        json.append(",\"created_at\":").append(escapeString(bundle.createdAt))
        json.append(",\"strings\":{")

        val sortedKeys = bundle.strings.keys.sorted()
        for ((i, key) in sortedKeys.withIndex()) {
            if (i > 0) json.append(',')
            json.append(escapeString(key)).append(':').append(escapeString(bundle.strings[key]!!))
        }

        json.append("}}")
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun escapeString(s: String): String {
        val result = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> result.append("\\\"")
                '\\' -> result.append("\\\\")
                '\b' -> result.append("\\b")
                '\u000C' -> result.append("\\f")
                '\n' -> result.append("\\n")
                '\r' -> result.append("\\r")
                '\t' -> result.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        result.append(String.format("\\u%04x", ch.code))
                    } else {
                        result.append(ch)
                    }
                }
            }
        }
        result.append('"')
        return result.toString()
    }
}
