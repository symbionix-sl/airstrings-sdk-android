package com.airstrings.sdk

import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.Experiment
import com.airstrings.sdk.models.StringBundle
import com.airstrings.sdk.models.StringEntry
import com.airstrings.sdk.models.StringFormat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@DisplayName("CanonicalJson")
class CanonicalJsonTest {

    private fun textEntry(value: String): StringEntry = StringEntry(value, StringFormat.TEXT)
    private fun icuEntry(value: String): StringEntry = StringEntry(value, StringFormat.ICU)

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
                "onboarding.welcome_title" to textEntry("Welcome to Acme"),
                "onboarding.welcome_body" to textEntry("Get started in minutes."),
                "settings.language" to textEntry("Language"),
                "items.count" to icuEntry("{count, plural, one {# item} other {# items}}"),
                "error.network" to textEntry("Something went wrong. Please try again."),
            ),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        val expected =
            """{"format_version":1,"project_id":"proj_a1b2c3d4e5f6","locale":"en-US","revision":42,"created_at":"2026-02-25T14:30:00Z","strings":{"error.network":{"format":"text","value":"Something went wrong. Please try again."},"items.count":{"format":"icu","value":"{count, plural, one {# item} other {# items}}"},"onboarding.welcome_body":{"format":"text","value":"Get started in minutes."},"onboarding.welcome_title":{"format":"text","value":"Welcome to Acme"},"settings.language":{"format":"text","value":"Language"}}}"""

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
            strings = mapOf(
                "z.last" to textEntry("Z"),
                "a.first" to textEntry("A"),
                "m.middle" to textEntry("M"),
            ),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.contains("\"a.first\":{\"format\":\"text\",\"value\":\"A\"},\"m.middle\":{\"format\":\"text\",\"value\":\"M\"},\"z.last\":{\"format\":\"text\",\"value\":\"Z\"}"))
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
            strings = mapOf("key" to textEntry("value")),
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
            strings = mapOf("key" to textEntry("line1\nline2\ttab \"quoted\" back\\slash")),
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
            strings = mapOf("key" to textEntry("before\u0001after")),
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

    @Test
    @DisplayName("icu format serialized correctly in canonical JSON")
    fun icuFormatSerialized() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = mapOf("count" to icuEntry("{n, plural, one {# item} other {# items}}")),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.contains("\"count\":{\"format\":\"icu\",\"value\":\"{n, plural, one {# item} other {# items}}\"}"))
    }

    @Test
    @DisplayName("string entry has sorted keys inside object (format before value)")
    fun stringEntrySortedKeysInsideObject() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = mapOf("hello" to textEntry("Hello")),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.contains("\"hello\":{\"format\":\"text\",\"value\":\"Hello\"}"))
    }

    @Test
    @DisplayName("mixed text and icu entries sorted correctly")
    fun mixedTextAndIcuEntries() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-01-01T00:00:00Z",
            keyId = "key_test_01",
            signature = "dummy",
            strings = mapOf(
                "b.text" to textEntry("Plain"),
                "a.icu" to icuEntry("{n, plural, one {#} other {#s}}"),
            ),
        )

        val result = String(CanonicalJson.signedContent(bundle), Charsets.UTF_8)

        assertTrue(result.contains("\"a.icu\":{\"format\":\"icu\""))
        assertTrue(result.contains("\"b.text\":{\"format\":\"text\""))
        assertTrue(result.indexOf("\"a.icu\"") < result.indexOf("\"b.text\""))
    }

    @Test
    @DisplayName("experiments contract example produces exact output")
    fun experimentsContractExample() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_x",
            locale = "en-US",
            revision = 42,
            createdAt = "2026-07-14T10:00:00Z",
            keyId = "key_prod_01",
            signature = "dummy",
            strings = mapOf(
                "checkout.cta" to StringEntry(
                    value = "Continue",
                    format = StringFormat.TEXT,
                    experiment = Experiment(
                        id = "exp_a1b2c3d4e5f6",
                        allocation = mapOf("control" to 50, "variant_a" to 50),
                        variants = mapOf("variant_a" to "Continue"),
                    ),
                ),
            ),
        )

        val result = String(CanonicalJson.experimentsSignedContent(bundle), Charsets.UTF_8)

        val expected =
            """{"format_version":1,"project_id":"proj_x","locale":"en-US","revision":42,"created_at":"2026-07-14T10:00:00Z","experiments":{"checkout.cta":{"allocation":{"control":50,"variant_a":50},"id":"exp_a1b2c3d4e5f6","variants":{"variant_a":"Continue"}}}}"""

        assertEquals(expected, result)
    }
}
