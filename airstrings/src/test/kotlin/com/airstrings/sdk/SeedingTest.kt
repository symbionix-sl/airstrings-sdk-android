package com.airstrings.sdk

import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.StringBundle
import com.airstrings.sdk.models.StringEntry
import com.airstrings.sdk.models.StringFormat
import com.airstrings.sdk.networking.BundleFetcher
import com.airstrings.sdk.networking.FetchResult
import com.airstrings.sdk.security.Base64Url
import com.airstrings.sdk.security.BundleVerifier
import com.airstrings.sdk.storage.BundleStore
import com.airstrings.sdk.storage.SeedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Seeding")
class SeedingTest {

    @TempDir
    lateinit var tempDir: File

    private val privateKey: Ed25519PrivateKeyParameters
    private val publicKeyBase64: String

    init {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        privateKey = pair.private as Ed25519PrivateKeyParameters
        publicKeyBase64 = Base64.getEncoder().encodeToString((pair.public as Ed25519PublicKeyParameters).encoded)
    }

    private fun textEntry(value: String): StringEntry = StringEntry(value, StringFormat.TEXT)

    private fun makeSignedBundle(
        projectId: String = PROJECT_ID,
        locale: String = "en",
        revision: Int,
        strings: Map<String, String>,
    ): StringBundle {
        val unsigned = StringBundle(
            formatVersion = 1,
            projectId = projectId,
            locale = locale,
            revision = revision,
            createdAt = "2026-06-10T00:00:00Z",
            keyId = publicKeyBase64,
            signature = "",
            strings = strings.mapValues { textEntry(it.value) },
        )

        val canonical = CanonicalJson.signedContent(unsigned)
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(canonical, 0, canonical.size)
        return unsigned.copy(signature = Base64Url.encode(signer.generateSignature()))
    }

    private fun encode(bundle: StringBundle): ByteArray {
        val stringsJson = JSONObject()
        for ((key, entry) in bundle.strings) {
            stringsJson.put(key, JSONObject().put("value", entry.value).put("format", entry.format.rawValue))
        }
        val json = JSONObject()
            .put("format_version", bundle.formatVersion)
            .put("project_id", bundle.projectId)
            .put("locale", bundle.locale)
            .put("revision", bundle.revision)
            .put("created_at", bundle.createdAt)
            .put("key_id", bundle.keyId)
            .put("signature", bundle.signature)
            .put("strings", stringsJson)
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun signedBundleBytes(
        projectId: String = PROJECT_ID,
        locale: String = "en",
        revision: Int,
        strings: Map<String, String>,
    ): ByteArray = encode(makeSignedBundle(projectId, locale, revision, strings))

    private class FailingBundleFetcher : BundleFetcher(baseUrl = "https://cdn.invalid") {
        override fun fetch(
            organizationId: String,
            projectId: String,
            environmentId: String,
            locale: String,
            ifNoneMatch: String?,
        ): FetchResult {
            throw IOException("offline")
        }
    }

    private class FixedBundleFetcher(private val data: ByteArray) : BundleFetcher(baseUrl = "https://cdn.invalid") {
        override fun fetch(
            organizationId: String,
            projectId: String,
            environmentId: String,
            locale: String,
            ifNoneMatch: String?,
        ): FetchResult {
            return FetchResult.Success(data = data, etag = null)
        }
    }

    private fun makeStore(): BundleStore = BundleStore(baseDirectory = tempDir)

    private fun makeSut(
        seedSource: SeedSource?,
        store: BundleStore = makeStore(),
        fetcher: BundleFetcher = FailingBundleFetcher(),
        scope: CoroutineScope = TestScope(),
    ): AirStrings {
        val configuration = AirStringsConfiguration(
            organizationId = "org_test12345678",
            projectId = PROJECT_ID,
            environmentId = ENV_ID,
            publicKeys = listOf(publicKeyBase64),
            locale = AirStringsLocale.Fixed("en"),
        )
        return AirStrings(
            fetcher = fetcher,
            verifier = BundleVerifier(publicKeys = listOf(publicKeyBase64)),
            store = store,
            scope = scope,
            configuration = configuration,
            seedSource = seedSource,
        )
    }

    @Test
    @DisplayName("offline cold start serves valid seed")
    fun offlineColdStartServesValidSeed() = runTest {
        val seed = signedBundleBytes(revision = 7, strings = mapOf("hello" to "Bonjour"))
        val store = makeStore()
        val sut = makeSut(seedSource = SeedSource { if (it == "en") seed else null }, store = store)

        sut.loadLocalBundle()
        sut.setLocale("en")

        assertEquals("Bonjour", sut.strings.value["hello"])
        assertTrue(sut.isReady.value)
        assertEquals(7, sut.revision.value)
        val persisted = store.load(PROJECT_ID, ENV_ID, "en")
        assertNotNull(persisted)
        assertContentEquals(seed, persisted.data)
    }

    @Test
    @DisplayName("tampered seed is rejected, never served, never cached")
    fun tamperedSeedRejected() {
        val valid = makeSignedBundle(revision = 3, strings = mapOf("hello" to "Hi"))
        val tampered = valid.copy(strings = mapOf("hello" to textEntry("HACKED")))
        val store = makeStore()
        val sut = makeSut(seedSource = SeedSource { encode(tampered) }, store = store)

        sut.loadLocalBundle()

        assertEquals(emptyMap(), sut.strings.value)
        assertFalse(sut.isReady.value)
        assertEquals("hello", sut["hello"])
        assertNull(store.load(PROJECT_ID, ENV_ID, "en"))
    }

    @Test
    @DisplayName("seed older than cache is ignored and store untouched")
    fun seedOlderThanCacheIgnored() {
        val store = makeStore()
        val cacheBytes = signedBundleBytes(revision = 5, strings = mapOf("greeting" to "Cached"))
        store.save(cacheBytes, PROJECT_ID, ENV_ID, "en", null)
        val seedBytes = signedBundleBytes(revision = 4, strings = mapOf("greeting" to "Seeded"))
        val sut = makeSut(seedSource = SeedSource { seedBytes }, store = store)

        sut.loadLocalBundle()

        assertEquals("Cached", sut.strings.value["greeting"])
        assertEquals(5, sut.revision.value)
        assertContentEquals(cacheBytes, store.load(PROJECT_ID, ENV_ID, "en")!!.data)
    }

    @Test
    @DisplayName("seed newer than cache is served and persisted")
    fun seedNewerThanCacheWins() {
        val store = makeStore()
        val cacheBytes = signedBundleBytes(revision = 5, strings = mapOf("greeting" to "Cached"))
        store.save(cacheBytes, PROJECT_ID, ENV_ID, "en", null)
        val seedBytes = signedBundleBytes(revision = 6, strings = mapOf("greeting" to "Seeded"))
        val sut = makeSut(seedSource = SeedSource { seedBytes }, store = store)

        sut.loadLocalBundle()

        assertEquals("Seeded", sut.strings.value["greeting"])
        assertEquals(6, sut.revision.value)
        assertTrue(sut.isReady.value)
        assertContentEquals(seedBytes, store.load(PROJECT_ID, ENV_ID, "en")!!.data)
    }

    @Test
    @DisplayName("revision tie keeps the cached bundle")
    fun revisionTieCacheWins() {
        val store = makeStore()
        val cacheBytes = signedBundleBytes(revision = 5, strings = mapOf("greeting" to "Cached"))
        store.save(cacheBytes, PROJECT_ID, ENV_ID, "en", null)
        val seedBytes = signedBundleBytes(revision = 5, strings = mapOf("greeting" to "Seeded"))
        val sut = makeSut(seedSource = SeedSource { seedBytes }, store = store)

        sut.loadLocalBundle()

        assertEquals("Cached", sut.strings.value["greeting"])
        assertEquals(5, sut.revision.value)
        assertContentEquals(cacheBytes, store.load(PROJECT_ID, ENV_ID, "en")!!.data)
    }

    @Test
    @DisplayName("no seed source behaves like the pre-seed SDK")
    fun noSeedSourceUnchangedBehavior() {
        val sut = makeSut(seedSource = null)

        sut.loadLocalBundle()

        assertEquals(emptyMap(), sut.strings.value)
        assertFalse(sut.isReady.value)
        assertEquals("any.key", sut["any.key"])
    }

    @Test
    @DisplayName("missing seed asset is a silent no-op")
    fun missingSeedAssetSilent() {
        val sut = makeSut(seedSource = SeedSource { null })

        sut.loadLocalBundle()

        assertEquals(emptyMap(), sut.strings.value)
        assertFalse(sut.isReady.value)
        assertEquals("any.key", sut["any.key"])
    }

    @Test
    @DisplayName("missing seed asset still serves cache")
    fun missingSeedAssetServesCache() {
        val store = makeStore()
        val cacheBytes = signedBundleBytes(revision = 2, strings = mapOf("greeting" to "Cached"))
        store.save(cacheBytes, PROJECT_ID, ENV_ID, "en", null)
        val sut = makeSut(seedSource = SeedSource { null }, store = store)

        sut.loadLocalBundle()

        assertEquals("Cached", sut.strings.value["greeting"])
        assertEquals(2, sut.revision.value)
        assertTrue(sut.isReady.value)
    }

    @Test
    @DisplayName("seed with wrong project_id is rejected")
    fun wrongProjectIdRejected() {
        val store = makeStore()
        val seedBytes = signedBundleBytes(projectId = "proj_other1234567", revision = 1, strings = mapOf("hello" to "Hi"))
        val sut = makeSut(seedSource = SeedSource { seedBytes }, store = store)

        sut.loadLocalBundle()

        assertEquals(emptyMap(), sut.strings.value)
        assertFalse(sut.isReady.value)
        assertNull(store.load(PROJECT_ID, ENV_ID, "en"))
    }

    @Test
    @DisplayName("seed whose locale differs from requested locale is rejected")
    fun localeMismatchRejected() {
        val store = makeStore()
        val seedBytes = signedBundleBytes(locale = "ja", revision = 1, strings = mapOf("hello" to "Konnichiwa"))
        val sut = makeSut(seedSource = SeedSource { seedBytes }, store = store)

        sut.loadLocalBundle()

        assertEquals(emptyMap(), sut.strings.value)
        assertFalse(sut.isReady.value)
        assertNull(store.load(PROJECT_ID, ENV_ID, "en"))
        assertNull(store.load(PROJECT_ID, ENV_ID, "ja"))
    }

    @Test
    @DisplayName("setLocale seeds the new locale")
    fun setLocaleSeedsNewLocale() = runTest {
        val store = makeStore()
        val frSeed = signedBundleBytes(locale = "fr", revision = 9, strings = mapOf("greeting" to "Bonjour"))
        val sut = makeSut(seedSource = SeedSource { if (it == "fr") frSeed else null }, store = store)

        sut.loadLocalBundle()
        assertFalse(sut.isReady.value)

        sut.setLocale("fr")

        assertEquals("Bonjour", sut.strings.value["greeting"])
        assertEquals(9, sut.revision.value)
        assertTrue(sut.isReady.value)
        assertContentEquals(frSeed, store.load(PROJECT_ID, ENV_ID, "fr")!!.data)
    }

    @Test
    @DisplayName("start loads the seed off the caller thread")
    fun startLoadsSeedOffCallerThread() = runTest {
        val seed = signedBundleBytes(revision = 7, strings = mapOf("hello" to "Bonjour"))
        val loadThreads = CopyOnWriteArrayList<Thread>()
        val callerThread = Thread.currentThread()
        val sut = makeSut(
            seedSource = SeedSource { locale ->
                loadThreads.add(Thread.currentThread())
                if (locale == "en") seed else null
            },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        sut.start().join()

        assertTrue(loadThreads.isNotEmpty())
        assertFalse(loadThreads.contains(callerThread))
        sut.close()
    }

    @Test
    @DisplayName("start applies the local candidate before the first refresh result")
    fun startAppliesLocalCandidateBeforeFirstRefreshResult() = runTest {
        val store = makeStore()
        val seed = signedBundleBytes(revision = 7, strings = mapOf("greeting" to "Seeded"))
        val stale = signedBundleBytes(revision = 5, strings = mapOf("greeting" to "Stale"))
        val sut = makeSut(
            seedSource = SeedSource { if (it == "en") seed else null },
            store = store,
            fetcher = FixedBundleFetcher(stale),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )

        sut.start().join()

        assertEquals("Seeded", sut.strings.value["greeting"])
        assertEquals(7, sut.revision.value)
        assertTrue(sut.isReady.value)
        assertContentEquals(seed, store.load(PROJECT_ID, ENV_ID, "en")!!.data)
        sut.close()
    }

    private companion object {
        const val PROJECT_ID = "proj_test12345678"
        const val ENV_ID = "env_test12345678"
    }
}
