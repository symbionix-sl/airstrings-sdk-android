package com.airstrings.sdk

import com.airstrings.sdk.security.BundleVerifier
import com.airstrings.sdk.storage.BundleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@OptIn(ExperimentalCoroutinesApi::class)
@EnabledIfEnvironmentVariable(named = "AIRSTRINGS_LIVE_E2E", matches = "1")
@DisplayName("Live failover E2E")
class LiveFailoverE2ETest {

    @TempDir
    lateinit var tempDir: File

    private val servers = mutableListOf<LocalHttpServer>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        servers.forEach { it.close() }
        Dispatchers.resetMain()
    }

    private fun env(name: String): String =
        requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) { "$name is required" }

    private fun track(server: LocalHttpServer): LocalHttpServer = server.also { servers.add(it) }

    @Test
    @DisplayName("unreachable CDN fails over to the live fallback and stays there")
    fun unreachableCDNFailsOverToLiveFallback() = runTest {
        val publicKey = env("AIRSTRINGS_E2E_PUBLIC_KEY")
        val cdnBase = System.getenv("AIRSTRINGS_E2E_CDN_BASE")?.takeIf { it.isNotBlank() }
            ?: track(LocalHttpServer.hang()).baseUrl
        val bootstrap = track(
            LocalHttpServer.respond(
                200,
                JSONObject()
                    .put("cdn_base_url", cdnBase)
                    .put("fallback_base_url", env("AIRSTRINGS_E2E_FALLBACK_BASE"))
                    .toString()
                    .toByteArray(Charsets.UTF_8),
            ),
        )
        val configuration = AirStringsConfiguration(
            organizationId = env("AIRSTRINGS_E2E_ORG"),
            projectId = env("AIRSTRINGS_E2E_PROJ"),
            environmentId = env("AIRSTRINGS_E2E_ENV"),
            publicKeys = listOf(publicKey),
            locale = AirStringsLocale.Fixed(env("AIRSTRINGS_E2E_LOCALE")),
            apiBaseURL = bootstrap.baseUrl,
        )
        val sut = AirStrings(
            httpClient = OkHttpClient(),
            verifier = BundleVerifier(publicKeys = listOf(publicKey)),
            store = BundleStore(baseDirectory = tempDir),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            configuration = configuration,
            seedSource = null,
        )

        val startMark = TimeSource.Monotonic.markNow()
        val started = withContext(Dispatchers.Default) {
            withTimeoutOrNull(15.seconds) { sut.start().join() }
        }
        val startElapsed = startMark.elapsedNow()
        println("Live failover: start ${startElapsed.inWholeMilliseconds} ms, revision ${sut.revision.value}")

        assertNotNull(started, "start() did not finish within 15s")
        assertTrue(sut.isReady.value, "not ready after ${startElapsed.inWholeMilliseconds} ms")
        val revision = sut.revision.value
        assertTrue(revision > 0, "revision $revision")
        assertTrue(
            startElapsed >= 5.seconds && startElapsed <= 12.seconds,
            "start took ${startElapsed.inWholeMilliseconds} ms",
        )

        val refreshMark = TimeSource.Monotonic.markNow()
        sut.refresh()
        val refreshElapsed = refreshMark.elapsedNow()
        println("Live failover: sticky refresh ${refreshElapsed.inWholeMilliseconds} ms")

        assertTrue(refreshElapsed < 2.seconds, "refresh took ${refreshElapsed.inWholeMilliseconds} ms")
        assertEquals(revision, sut.revision.value)
        assertTrue(sut.isReady.value)
        sut.close()
    }
}
