package com.airstrings.sdk

import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.StringBundle
import com.airstrings.sdk.models.StringEntry
import com.airstrings.sdk.models.StringFormat
import com.airstrings.sdk.security.Base64Url
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
import okhttp3.OkHttpClient
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.json.JSONObject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Failover delivery")
class FailoverDeliveryTest {

    @TempDir
    lateinit var tempDir: File

    private val privateKey: Ed25519PrivateKeyParameters
    private val publicKeyBase64: String
    private val servers = mutableListOf<LocalHttpServer>()

    init {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        privateKey = pair.private as Ed25519PrivateKeyParameters
        publicKeyBase64 = Base64.getEncoder().encodeToString((pair.public as Ed25519PublicKeyParameters).encoded)
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        servers.forEach { it.close() }
        Dispatchers.resetMain()
    }

    private fun makeSignedBundle(revision: Int, strings: Map<String, String>): StringBundle {
        val unsigned = StringBundle(
            formatVersion = 1,
            projectId = PROJECT_ID,
            locale = "en",
            revision = revision,
            createdAt = "2026-09-16T00:00:00Z",
            keyId = publicKeyBase64,
            signature = "",
            strings = strings.mapValues { StringEntry(it.value, StringFormat.TEXT) },
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

    private fun signedBundleBytes(revision: Int, strings: Map<String, String>): ByteArray =
        encode(makeSignedBundle(revision, strings))

    private fun track(server: LocalHttpServer): LocalHttpServer = server.also { servers.add(it) }

    private fun bootstrapServer(json: JSONObject): LocalHttpServer =
        track(LocalHttpServer.respond(200, json.toString().toByteArray(Charsets.UTF_8)))

    private fun makeSut(apiBaseURL: String, store: BundleStore): AirStrings {
        val configuration = AirStringsConfiguration(
            organizationId = ORG_ID,
            projectId = PROJECT_ID,
            environmentId = ENV_ID,
            publicKeys = listOf(publicKeyBase64),
            locale = AirStringsLocale.Fixed("en"),
            apiBaseURL = apiBaseURL,
        )
        return AirStrings(
            httpClient = OkHttpClient(),
            verifier = BundleVerifier(publicKeys = listOf(publicKeyBase64)),
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            configuration = configuration,
            seedSource = null,
        )
    }

    @Test
    @DisplayName("bootstrap fallback is used when the CDN is unreachable")
    fun bootstrapFallbackUsedWhenCDNUnreachable() = runTest {
        val cdn = track(LocalHttpServer.hang())
        val fallback = track(LocalHttpServer.respond(200, signedBundleBytes(3, mapOf("greeting" to "Fallback"))))
        val bootstrap = bootstrapServer(
            JSONObject().put("cdn_base_url", cdn.baseUrl).put("fallback_base_url", fallback.baseUrl),
        )
        val sut = makeSut(bootstrap.baseUrl, BundleStore(baseDirectory = tempDir))

        sut.start().join()

        assertEquals("Fallback", sut.strings.value["greeting"])
        assertEquals(3, sut.revision.value)
        assertTrue(sut.isReady.value)
        assertEquals(1, fallback.hits.get())
        assertTrue(fallback.requestHeads.single().startsWith("GET /$ORG_ID/$PROJECT_ID/$ENV_ID/en/bundle.json "))
        sut.close()
    }

    @Test
    @DisplayName("bootstrap without fallback field loads from the CDN")
    fun bootstrapWithoutFallbackFieldLoadsFromCDN() = runTest {
        val cdn = track(LocalHttpServer.respond(200, signedBundleBytes(4, mapOf("greeting" to "Primary"))))
        val fallback = track(LocalHttpServer.respond(200, signedBundleBytes(9, mapOf("greeting" to "Fallback"))))
        val bootstrap = bootstrapServer(
            JSONObject().put("cdn_base_url", cdn.baseUrl).put("unknown_field", fallback.baseUrl),
        )
        val sut = makeSut(bootstrap.baseUrl, BundleStore(baseDirectory = tempDir))

        sut.start().join()

        assertEquals("Primary", sut.strings.value["greeting"])
        assertEquals(4, sut.revision.value)
        assertEquals(1, cdn.hits.get())
        assertEquals(0, fallback.hits.get())
        sut.close()
    }

    @Test
    @DisplayName("invalid signature from fallback is rejected and cache kept")
    fun invalidSignatureFromFallbackRejectedCacheKept() = runTest {
        val store = BundleStore(baseDirectory = tempDir)
        val cacheBytes = signedBundleBytes(2, mapOf("greeting" to "Cached"))
        store.save(cacheBytes, PROJECT_ID, ENV_ID, "en", null)
        val valid = makeSignedBundle(3, mapOf("greeting" to "Fallback"))
        val tampered = valid.copy(strings = mapOf("greeting" to StringEntry("HACKED", StringFormat.TEXT)))
        val cdn = track(LocalHttpServer.refused())
        val fallback = track(LocalHttpServer.respond(200, encode(tampered)))
        val bootstrap = bootstrapServer(
            JSONObject().put("cdn_base_url", cdn.baseUrl).put("fallback_base_url", fallback.baseUrl),
        )
        val sut = makeSut(bootstrap.baseUrl, store)

        sut.start().join()

        assertEquals(1, fallback.hits.get())
        assertEquals("Cached", sut.strings.value["greeting"])
        assertEquals(2, sut.revision.value)
        assertTrue(sut.isReady.value)
        assertContentEquals(cacheBytes, store.load(PROJECT_ID, ENV_ID, "en")!!.data)
        sut.close()
    }

    @Test
    @DisplayName("both hosts failing keeps the cache")
    fun bothHostsFailKeepsCache() = runTest {
        val store = BundleStore(baseDirectory = tempDir)
        val cacheBytes = signedBundleBytes(2, mapOf("greeting" to "Cached"))
        store.save(cacheBytes, PROJECT_ID, ENV_ID, "en", null)
        val cdn = track(LocalHttpServer.refused())
        val fallback = track(LocalHttpServer.respond(503))
        val bootstrap = bootstrapServer(
            JSONObject().put("cdn_base_url", cdn.baseUrl).put("fallback_base_url", fallback.baseUrl),
        )
        val sut = makeSut(bootstrap.baseUrl, store)

        sut.start().join()

        assertEquals(1, fallback.hits.get())
        assertEquals("Cached", sut.strings.value["greeting"])
        assertEquals(2, sut.revision.value)
        assertTrue(sut.isReady.value)
        assertContentEquals(cacheBytes, store.load(PROJECT_ID, ENV_ID, "en")!!.data)
        sut.close()
    }

    private companion object {
        const val ORG_ID = "org_test12345678"
        const val PROJECT_ID = "proj_test12345678"
        const val ENV_ID = "env_test12345678"
    }
}
