package com.airstrings.sdk

import com.airstrings.sdk.security.Base64Url
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@DisplayName("Base64Url")
class Base64UrlTest {

    @Test
    @DisplayName("decode valid base64url")
    fun decodeValidBase64url() {
        val decoded = Base64Url.decode("SGVsbG8")
        assertNotNull(decoded)
        assertEquals("Hello", String(decoded, Charsets.UTF_8))
    }

    @Test
    @DisplayName("decode with URL-safe characters")
    fun decodeWithUrlSafeCharacters() {
        val standard = java.util.Base64.getDecoder().decode("a+b/cw==")
        val decoded = Base64Url.decode("a-b_cw")
        assertNotNull(decoded)
        assertContentEquals(standard, decoded)
    }

    @Test
    @DisplayName("decode handles missing padding")
    fun decodeHandlesMissingPadding() {
        val decoded = Base64Url.decode("YWI")
        assertNotNull(decoded)
        assertEquals("ab", String(decoded, Charsets.UTF_8))
    }

    @Test
    @DisplayName("encode then decode round-trip")
    fun encodeThenDecode() {
        val original = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte())
        val encoded = Base64Url.encode(original)
        val decoded = Base64Url.decode(encoded)
        assertNotNull(decoded)
        assertContentEquals(original, decoded)
        assert(!encoded.contains('+')) { "Should not contain +" }
        assert(!encoded.contains('/')) { "Should not contain /" }
        assert(!encoded.contains('=')) { "Should not contain =" }
    }

    @Test
    @DisplayName("64-byte signature produces 86-char encoding")
    fun decode64ByteSignature() {
        val signatureBytes = ByteArray(64) { 0xAB.toByte() }
        val encoded = Base64Url.encode(signatureBytes)
        assertEquals(86, encoded.length)
        val decoded = Base64Url.decode(encoded)
        assertNotNull(decoded)
        assertEquals(64, decoded.size)
        assertContentEquals(signatureBytes, decoded)
    }

    @Test
    @DisplayName("decode empty string")
    fun decodeEmptyString() {
        val decoded = Base64Url.decode("")
        assertNotNull(decoded)
        assertEquals(0, decoded.size)
    }
}
