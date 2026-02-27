package com.airstrings.sdk

import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.StringBundle
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("CanonicalJson")
class CanonicalJsonTest {

    @Test
    @DisplayName("contract example produces exact output")
    fun contractExample() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_a1b2c3d4e5f6",
            locale = "en-US",
            revision = 42,
            createdAt = "2026-02-25T14:30:00Z",
            keyId = "key_prod_01",
            signature = "dummy",
            strings = mapOf(
                "onboarding.welcome_title" to "Welcome to Acme",
                "onboarding.welcome_body" to "Get started in minutes.",
                "settings.language" to "Language",
                "error.network" to "Something went wrong. Please try again.",
            ),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        val expected =
            """{"format_version":1,"project_id":"proj_a1b2c3d4e5f6","locale":"en-US","revision":42,"created_at":"2026-02-25T14:30:00Z","strings":{"error.network":"Something went wrong. Please try again.","onboarding.welcome_body":"Get started in minutes.","onboarding.welcome_title":"Welcome to Acme","settings.language":"Language"}}"""

        assertEquals(expected, result)
    }

    @Test
    @DisplayName("keys sorted alphabetically")
    fun keysSortedAlphabetically() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = mapOf("z.last" to "Z", "a.first" to "A", "m.middle" to "M"),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.contains("\"a.first\":\"A\",\"m.middle\":\"M\",\"z.last\":\"Z\""))
    }

    @Test
    @DisplayName("no whitespace")
    fun noWhitespace() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = mapOf("key" to "value"),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertFalse(result.contains(" "))
        assertFalse(result.contains("\n"))
    }

    @Test
    @DisplayName("integers not floats")
    fun integersNotFloats() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 100,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = emptyMap(),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.contains("\"format_version\":1,"))
        assertTrue(result.contains("\"revision\":100,\"created_at\""))
        assertFalse(result.contains("1.0"))
        assertFalse(result.contains("100.0"))
    }

    @Test
    @DisplayName("string escaping")
    fun stringEscaping() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = mapOf("key" to "line1\nline2\ttab \"quoted\" back\\slash"),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.contains("line1\\nline2\\ttab \\\"quoted\\\" back\\\\slash"))
    }

    @Test
    @DisplayName("control character escaping")
    fun controlCharacterEscaping() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = mapOf("key" to "before\u0001after"),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.contains("before\\u0001after"))
    }

    @Test
    @DisplayName("empty strings object")
    fun emptyStringsObject() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = emptyMap(),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.endsWith("\"strings\":{}}"))
    }
}
