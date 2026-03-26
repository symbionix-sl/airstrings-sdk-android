package com.airstrings.sdk

import com.airstrings.sdk.models.StringEntry
import com.airstrings.sdk.models.StringFormat
import com.airstrings.sdk.networking.BundleFetcher
import com.airstrings.sdk.security.BundleVerifier
import com.airstrings.sdk.storage.BundleStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AirStrings")
class AirStringsTest {

    private fun makeConfig(
        locale: AirStringsLocale = AirStringsLocale.Fixed("en"),
    ): AirStringsConfiguration {
        return AirStringsConfiguration(
            organizationId = "org_test12345678",
            projectId = "proj_test12345678",
            environmentId = "env_test12345678",
            publicKeys = emptyList(),
            locale = locale,
        )
    }

    private fun makeSut(
        config: AirStringsConfiguration = makeConfig(),
    ): AirStrings {
        val testScope = TestScope()
        return AirStrings(
            fetcher = BundleFetcher(),
            verifier = BundleVerifier(publicKeys = config.publicKeys),
            store = BundleStore(
                baseDirectory = File(System.getProperty("java.io.tmpdir"), "airstrings-test-${System.nanoTime()}"),
            ),
            scope = testScope,
            configuration = config,
        )
    }

    private fun makeSutWithStrings(
        strings: Map<String, StringEntry>,
    ): AirStrings {
        val sut = makeSut()
        sut.applyBundle(strings, revision = 1)
        return sut
    }

    @Test
    @DisplayName("subscript returns fallback when no strings")
    fun subscriptReturnsFallbackWhenNoStrings() {
        val sut = makeSut()
        assertEquals("nonexistent.key", sut["nonexistent.key"])
        assertEquals("onboarding.title", sut["onboarding.title"])
    }

    @Test
    @DisplayName("initial state")
    fun initialState() {
        val sut = makeSut()
        assertEquals("en", sut.currentLocale.value)
        assertEquals(0, sut.revision.value)
        assertFalse(sut.isReady.value)
    }

    @Test
    @DisplayName("fixed locale resolution")
    fun fixedLocaleResolution() {
        val config = makeConfig(locale = AirStringsLocale.Fixed("it"))
        val sut = makeSut(config)
        assertEquals("it", sut.currentLocale.value)
    }

    @Test
    @DisplayName("initial revision is zero")
    fun initialRevisionIsZero() {
        val sut = makeSut()
        assertEquals(0, sut.revision.value)
    }

    @Test
    @DisplayName("initial strings map is empty")
    fun initialStringsMapIsEmpty() {
        val sut = makeSut()
        assertEquals(emptyMap(), sut.strings.value)
    }

    @Test
    @DisplayName("format returns key as fallback when no strings loaded")
    fun formatReturnsFallbackWhenNoStrings() {
        val sut = makeSut()
        assertEquals("missing.key", sut.format("missing.key", mapOf("count" to 5)))
    }

    @Test
    @DisplayName("format returns value as-is for text format")
    fun formatReturnsValueForTextFormat() {
        val sut = makeSutWithStrings(
            strings = mapOf("greeting" to StringEntry("Hello World", StringFormat.TEXT)),
        )
        assertEquals("Hello World", sut.format("greeting", mapOf("name" to "Alice")))
    }

    @Test
    @DisplayName("format returns raw pattern for icu format in JVM tests (fallback behavior)")
    fun formatReturnsRawPatternForIcuInJvm() {
        // In JVM unit tests, android.icu.text.MessageFormat is not available.
        // The format method catches the exception and returns the raw pattern.
        val pattern = "{count, plural, one {# item} other {# items}}"
        val sut = makeSutWithStrings(
            strings = mapOf("items" to StringEntry(pattern, StringFormat.ICU)),
        )
        assertEquals(pattern, sut.format("items", mapOf("count" to 5)))
    }

    @Test
    @DisplayName("get returns raw value for both text and icu entries")
    fun getReturnsRawValueForBothFormats() {
        val icuPattern = "{count, plural, one {# item} other {# items}}"
        val sut = makeSutWithStrings(
            strings = mapOf(
                "greeting" to StringEntry("Hello", StringFormat.TEXT),
                "items" to StringEntry(icuPattern, StringFormat.ICU),
            ),
        )
        assertEquals("Hello", sut["greeting"])
        assertEquals(icuPattern, sut["items"])
    }

    @Test
    @DisplayName("strings StateFlow contains raw values")
    fun stringsStateFlowContainsRawValues() {
        val icuPattern = "{n, plural, one {#} other {#s}}"
        val sut = makeSutWithStrings(
            strings = mapOf(
                "plain" to StringEntry("Text", StringFormat.TEXT),
                "pattern" to StringEntry(icuPattern, StringFormat.ICU),
            ),
        )
        assertEquals("Text", sut.strings.value["plain"])
        assertEquals(icuPattern, sut.strings.value["pattern"])
    }

    @Test
    @DisplayName("format returns key when key not found")
    fun formatReturnsKeyWhenNotFound() {
        val sut = makeSutWithStrings(
            strings = mapOf("existing" to StringEntry("value", StringFormat.TEXT)),
        )
        assertEquals("missing.key", sut.format("missing.key", emptyMap()))
    }

    @Test
    @DisplayName("text format ignores args")
    fun textFormatIgnoresArgs() {
        val sut = makeSutWithStrings(
            strings = mapOf("msg" to StringEntry("Static text", StringFormat.TEXT)),
        )
        assertEquals("Static text", sut.format("msg", mapOf("unused" to 42, "also_unused" to "value")))
    }
}
