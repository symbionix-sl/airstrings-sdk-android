package com.airstrings.sdk

import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.StringBundle
import com.airstrings.sdk.networking.BundleFetcher
import com.airstrings.sdk.security.Base64Url
import com.airstrings.sdk.security.BundleVerifier
import com.airstrings.sdk.storage.BundleStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("AirStrings")
class AirStringsTest {

    private fun makeConfig(
        locale: AirStringsLocale = AirStringsLocale.Fixed("en"),
    ): AirStringsConfiguration {
        return AirStringsConfiguration(
            projectId = "proj_test12345678",
            publicKeys = emptyMap(),
            locale = locale,
            baseUrl = "https://localhost:9999",
        )
    }

    private fun makeSut(
        config: AirStringsConfiguration = makeConfig(),
    ): AirStrings {
        val testScope = TestScope()
        return AirStrings(
            fetcher = BundleFetcher(baseUrl = config.baseUrl),
            verifier = BundleVerifier(publicKeys = config.publicKeys),
            store = BundleStore(
                baseDirectory = java.io.File(System.getProperty("java.io.tmpdir"), "airstrings-test-${System.nanoTime()}"),
            ),
            scope = testScope,
            configuration = config,
        )
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
}
