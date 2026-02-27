package com.airstrings.sdk.security

/**
 * Base64url encoding/decoding per RFC 4648 section 5.
 * Uses `-` instead of `+`, `_` instead of `/`, no padding.
 */
internal object Base64Url {

    fun decode(string: String): ByteArray? {
        if (string.isEmpty()) return ByteArray(0)
        var base64 = string
            .replace('-', '+')
            .replace('_', '/')
        val remainder = base64.length % 4
        if (remainder > 0) {
            base64 += "=".repeat(4 - remainder)
        }
        return try {
            java.util.Base64.getDecoder().decode(base64)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun encode(data: ByteArray): String {
        return java.util.Base64.getEncoder().encodeToString(data)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')
    }
}
