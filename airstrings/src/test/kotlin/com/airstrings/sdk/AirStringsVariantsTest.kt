package com.airstrings.sdk

import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.Experiment
import com.airstrings.sdk.models.StringBundle
import com.airstrings.sdk.models.StringEntry
import com.airstrings.sdk.models.StringFormat
import com.airstrings.sdk.networking.BundleFetcher
import com.airstrings.sdk.security.Base64Url
import com.airstrings.sdk.security.BundleVerifier
import com.airstrings.sdk.storage.BundleStore
import com.airstrings.sdk.storage.SeedSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("AirStrings variants")
class AirStringsVariantsTest {

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

    private val exposures = CopyOnWriteArrayList<ExposureEvent>()
    private var stringsUpdatedCount = 0

    private enum class ExperimentsSig { VALID, TAMPERED, ABSENT }

    private fun ctaEntry(): StringEntry = StringEntry(
        value = "Continue",
        format = StringFormat.TEXT,
        experiment = Experiment(
            id = "exp_checkout_cta",
            allocation = mapOf("control" to 50, "variant_a" to 50),
            variants = mapOf("variant_a" to "Variant A CTA"),
        ),
    )

    private fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun makeSignedBundle(
        revision: Int,
        strings: Map<String, StringEntry>,
        locale: String = "en",
        experimentsSig: ExperimentsSig = ExperimentsSig.VALID,
    ): StringBundle {
        val unsigned = StringBundle(
            formatVersion = 1,
            projectId = PROJECT_ID,
            locale = locale,
            revision = revision,
            createdAt = "2026-07-14T10:00:00Z",
            keyId = publicKeyBase64,
            signature = "",
            strings = strings,
        )
        val baseSig = Base64Url.encode(sign(CanonicalJson.signedContent(unsigned)))
        val expSig = when (experimentsSig) {
            ExperimentsSig.VALID -> Base64Url.encode(sign(CanonicalJson.experimentsSignedContent(unsigned)))
            ExperimentsSig.TAMPERED -> Base64Url.encode(sign("tampered-experiments".toByteArray(Charsets.UTF_8)))
            ExperimentsSig.ABSENT -> null
        }
        return unsigned.copy(signature = baseSig, experimentsSignature = expSig)
    }

    private fun encode(bundle: StringBundle): ByteArray {
        val stringsJson = JSONObject()
        for ((key, entry) in bundle.strings) {
            val entryJson = JSONObject()
                .put("value", entry.value)
                .put("format", entry.format.rawValue)
            entry.experiment?.let { exp ->
                val allocation = JSONObject()
                for ((name, weight) in exp.allocation) allocation.put(name, weight)
                val variants = JSONObject()
                for ((name, value) in exp.variants) variants.put(name, value)
                entryJson.put(
                    "experiment",
                    JSONObject()
                        .put("id", exp.id)
                        .put("allocation", allocation)
                        .put("variants", variants),
                )
            }
            stringsJson.put(key, entryJson)
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
        bundle.experimentsSignature?.let { json.put("experiments_signature", it) }
        return json.toString().toByteArray(Charsets.UTF_8)
    }

    private fun makeSut(
        seedSource: SeedSource? = null,
        store: BundleStore = BundleStore(baseDirectory = tempDir),
    ): AirStrings {
        val configuration = AirStringsConfiguration(
            organizationId = "org_test12345678",
            projectId = PROJECT_ID,
            environmentId = ENV_ID,
            publicKeys = listOf(publicKeyBase64),
            locale = AirStringsLocale.Fixed("en"),
        )
        val sut = AirStrings(
            fetcher = BundleFetcher(baseUrl = "https://cdn.invalid"),
            verifier = BundleVerifier(publicKeys = listOf(publicKeyBase64)),
            store = store,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            configuration = configuration,
            seedSource = seedSource,
        )
        sut.onExposure = { exposures.add(it) }
        sut.onStringsUpdated = { _, _ -> stringsUpdatedCount++ }
        return sut
    }

    private fun sutWithSeed(bundle: StringBundle): AirStrings {
        val bytes = encode(bundle)
        val sut = makeSut(seedSource = SeedSource { if (it == bundle.locale) bytes else null })
        sut.loadLocalBundle()
        return sut
    }

    @Test
    @DisplayName("signed experiments + assignment serves the variant value")
    fun signedExperimentsWithAssignmentServesVariantValue() {
        val sut = sutWithSeed(makeSignedBundle(revision = 3, strings = mapOf("checkout.cta" to ctaEntry())))
        assertEquals("Continue", sut["checkout.cta"])

        sut.setAssignmentId("user_1")
        assertEquals("Variant A CTA", sut["checkout.cta"])
        assertEquals("Variant A CTA", sut.format("checkout.cta", emptyMap()))
    }

    @Test
    @DisplayName("no assignment serves base and stages no exposure")
    fun noAssignmentServesBaseWithoutExposure() {
        val sut = sutWithSeed(makeSignedBundle(revision = 3, strings = mapOf("checkout.cta" to ctaEntry())))

        assertEquals("Continue", sut["checkout.cta"])
        assertTrue(exposures.isEmpty())
        assertTrue(sut.isReady.value)
        assertEquals(3, sut.revision.value)
    }

    @Test
    @DisplayName("tampered experiments signature serves base but applies bundle")
    fun tamperedExperimentsSignatureServesBaseButAppliesBundle() {
        val sut = sutWithSeed(
            makeSignedBundle(
                revision = 5,
                strings = mapOf("checkout.cta" to ctaEntry()),
                experimentsSig = ExperimentsSig.TAMPERED,
            ),
        )

        sut.setAssignmentId("user_1")
        assertEquals("Continue", sut["checkout.cta"])
        assertTrue(sut.isReady.value)
        assertEquals(5, sut.revision.value)
        assertTrue(exposures.isEmpty())
    }

    @Test
    @DisplayName("absent experiments signature serves base")
    fun absentExperimentsSignatureServesBase() {
        val sut = sutWithSeed(
            makeSignedBundle(
                revision = 5,
                strings = mapOf("checkout.cta" to ctaEntry()),
                experimentsSig = ExperimentsSig.ABSENT,
            ),
        )

        sut.setAssignmentId("user_1")
        assertEquals("Continue", sut["checkout.cta"])
        assertTrue(sut.isReady.value)
        assertTrue(exposures.isEmpty())
    }

    @Test
    @DisplayName("exactly one variant exposure, deduped across reads")
    fun exactlyOneVariantExposureDedupedAcrossReads() {
        val sut = sutWithSeed(makeSignedBundle(revision = 3, strings = mapOf("checkout.cta" to ctaEntry())))
        sut.setAssignmentId("user_1")

        sut["checkout.cta"]
        sut["checkout.cta"]
        sut.format("checkout.cta", emptyMap())

        assertEquals(1, exposures.size)
        val event = exposures.first()
        assertEquals("checkout.cta", event.key)
        assertEquals("exp_checkout_cta", event.experimentId)
        assertEquals("variant_a", event.variant)
        assertEquals("user_1", event.assignmentId)
        assertEquals("en", event.locale)
    }

    @Test
    @DisplayName("exposure deduped across re-applied bundles")
    fun exposureDedupedAcrossReappliedBundles() {
        val bundle = makeSignedBundle(revision = 3, strings = mapOf("checkout.cta" to ctaEntry()))
        val bytes = encode(bundle)
        val sut = makeSut(seedSource = SeedSource { if (it == "en") bytes else null })
        sut.loadLocalBundle()
        sut.setAssignmentId("user_1")

        sut["checkout.cta"]
        assertEquals(1, exposures.size)

        sut.loadLocalBundle()
        sut["checkout.cta"]

        assertEquals(1, exposures.size)
        assertEquals("Variant A CTA", sut["checkout.cta"])
    }

    @Test
    @DisplayName("control assignment records a control exposure")
    fun controlExposureRecordedAsControl() {
        val sut = sutWithSeed(makeSignedBundle(revision = 3, strings = mapOf("checkout.cta" to ctaEntry())))
        sut.setAssignmentId("user_2")

        assertEquals("Continue", sut["checkout.cta"])

        assertEquals(1, exposures.size)
        val event = exposures.first()
        assertEquals("control", event.variant)
        assertEquals("exp_checkout_cta", event.experimentId)
        assertEquals("checkout.cta", event.key)
        assertEquals("user_2", event.assignmentId)
    }

    @Test
    @DisplayName("pre-variants bundle behaves as before")
    fun preVariantsBundleBehavesAsBefore() {
        val sut = sutWithSeed(
            makeSignedBundle(
                revision = 4,
                strings = mapOf("greeting" to StringEntry("Hello", StringFormat.TEXT)),
                experimentsSig = ExperimentsSig.ABSENT,
            ),
        )
        sut.setAssignmentId("user_1")

        assertEquals("Hello", sut["greeting"])
        assertEquals("Hello", sut.format("greeting", emptyMap()))
        assertTrue(sut.isReady.value)
        assertEquals(4, sut.revision.value)
        assertTrue(exposures.isEmpty())
    }

    @Test
    @DisplayName("setAssignmentId switches served value and notifies")
    fun setAssignmentIdSwitchesServedValueAndNotifies() {
        val sut = sutWithSeed(makeSignedBundle(revision = 3, strings = mapOf("checkout.cta" to ctaEntry())))
        assertEquals("Continue", sut["checkout.cta"])

        stringsUpdatedCount = 0
        sut.setAssignmentId("user_1")
        assertEquals("Variant A CTA", sut["checkout.cta"])
        assertEquals(1, stringsUpdatedCount)

        sut.setAssignmentId(null)
        assertEquals("Continue", sut["checkout.cta"])
        assertEquals(2, stringsUpdatedCount)
    }

    @Test
    @DisplayName("cache load path serves variants")
    fun cacheLoadPathServesVariants() {
        val store = BundleStore(baseDirectory = tempDir)
        val bytes = encode(makeSignedBundle(revision = 6, strings = mapOf("checkout.cta" to ctaEntry())))
        store.save(bytes, PROJECT_ID, ENV_ID, "en", "\"rev:6\"")

        val sut = makeSut(store = store)
        sut.loadLocalBundle()
        sut.setAssignmentId("user_1")

        assertEquals("Variant A CTA", sut["checkout.cta"])
        assertEquals(6, sut.revision.value)
    }

    private companion object {
        const val PROJECT_ID = "proj_test12345678"
        const val ENV_ID = "env_test12345678"
    }
}
