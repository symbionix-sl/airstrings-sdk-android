package com.airstrings.sdk

import java.util.Base64
import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.StringBundle
import com.airstrings.sdk.models.StringEntry
import com.airstrings.sdk.models.StringFormat
import com.airstrings.sdk.security.Base64Url
import com.airstrings.sdk.security.BundleVerifier
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertIs

@DisplayName("BundleVerifier")
class BundleVerifierTest {

    private data class KeyPair(
        val privateKey: Ed25519PrivateKeyParameters,
        val publicKey: Ed25519PublicKeyParameters,
    ) {
        /** Base64-encoded public key (standard encoding, no wrapping). */
        val publicKeyBase64: String
            get() = Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    private fun generateKeyPair(): KeyPair {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val pair = generator.generateKeyPair()
        return KeyPair(
            privateKey = pair.private as Ed25519PrivateKeyParameters,
            publicKey = pair.public as Ed25519PublicKeyParameters,
        )
    }

    private fun sign(data: ByteArray, privateKey: Ed25519PrivateKeyParameters): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun textEntry(value: String): StringEntry = StringEntry(value, StringFormat.TEXT)

    private fun makeSignedBundle(
        formatVersion: Int = 1,
        projectId: String = "proj_test12345678",
        locale: String = "en",
        revision: Int = 1,
        createdAt: String = "2026-02-25T14:30:00Z",
        keyId: String? = null,
        strings: Map<String, StringEntry> = mapOf("hello" to textEntry("Hello World")),
        privateKey: Ed25519PrivateKeyParameters,
        publicKey: Ed25519PublicKeyParameters,
    ): StringBundle {
        val resolvedKeyId = keyId ?: Base64.getEncoder().encodeToString(publicKey.encoded)

        val unsigned = StringBundle(
            formatVersion = formatVersion,
            projectId = projectId,
            locale = locale,
            revision = revision,
            createdAt = createdAt,
            keyId = resolvedKeyId,
            signature = "",
            strings = strings,
        )

        val canonicalBytes = CanonicalJson.signedContent(unsigned)
        val signatureBytes = sign(canonicalBytes, privateKey)
        val signatureBase64url = Base64Url.encode(signatureBytes)

        return unsigned.copy(signature = signatureBase64url)
    }

    @Test
    @DisplayName("valid signature passes")
    fun validSignature() {
        val keyPair = generateKeyPair()
        val bundle = makeSignedBundle(
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
        )
        val verifier = BundleVerifier(
            publicKeys = listOf(keyPair.publicKeyBase64),
        )

        assertDoesNotThrow { verifier.verify(bundle) }
    }

    @Test
    @DisplayName("wrong key fails verification")
    fun wrongSignatureThrows() {
        val signingKey = generateKeyPair()
        val wrongKey = generateKeyPair()
        val bundle = makeSignedBundle(
            keyId = wrongKey.publicKeyBase64,
            privateKey = signingKey.privateKey,
            publicKey = signingKey.publicKey,
        )
        val verifier = BundleVerifier(
            publicKeys = listOf(wrongKey.publicKeyBase64),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(bundle) }
        assertIs<AirStringsError.SignatureVerificationFailed>(error)
    }

    @Test
    @DisplayName("unknown key_id throws")
    fun unknownKeyIdThrows() {
        val keyPair = generateKeyPair()
        val otherKey = generateKeyPair()
        val bundle = makeSignedBundle(
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
        )
        val verifier = BundleVerifier(
            publicKeys = listOf(otherKey.publicKeyBase64),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(bundle) }
        assertIs<AirStringsError.UnknownKeyId>(error)
        assertEquals(keyPair.publicKeyBase64, (error as AirStringsError.UnknownKeyId).keyId)
    }

    @Test
    @DisplayName("unsupported format_version throws")
    fun unsupportedFormatVersionThrows() {
        val keyPair = generateKeyPair()
        val bundle = makeSignedBundle(
            formatVersion = 99,
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
        )
        val verifier = BundleVerifier(
            publicKeys = listOf(keyPair.publicKeyBase64),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(bundle) }
        assertIs<AirStringsError.UnsupportedFormatVersion>(error)
        assertEquals(99, (error as AirStringsError.UnsupportedFormatVersion).version)
    }

    @Test
    @DisplayName("invalid signature encoding throws")
    fun invalidSignatureEncodingThrows() {
        val keyPair = generateKeyPair()
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-02-25T14:30:00Z",
            keyId = keyPair.publicKeyBase64,
            signature = "not-valid-base64url-!!@@##",
            strings = mapOf("hello" to textEntry("Hello")),
        )

        val verifier = BundleVerifier(
            publicKeys = listOf(keyPair.publicKeyBase64),
        )

        assertThrows<AirStringsError> { verifier.verify(bundle) }
    }

    @Test
    @DisplayName("signature with multiple strings verifies")
    fun signatureWithMultipleStrings() {
        val keyPair = generateKeyPair()
        val bundle = makeSignedBundle(
            strings = mapOf(
                "z.last" to textEntry("Last"),
                "a.first" to textEntry("First"),
                "m.middle" to textEntry("Middle"),
            ),
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
        )

        val verifier = BundleVerifier(
            publicKeys = listOf(keyPair.publicKeyBase64),
        )

        assertDoesNotThrow { verifier.verify(bundle) }
    }

    @Test
    @DisplayName("tampered strings fail verification")
    fun tamperedStringsFailVerification() {
        val keyPair = generateKeyPair()
        val original = makeSignedBundle(
            strings = mapOf("key" to textEntry("original")),
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
        )

        val tampered = original.copy(strings = mapOf("key" to textEntry("tampered")))

        val verifier = BundleVerifier(
            publicKeys = listOf(keyPair.publicKeyBase64),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(tampered) }
        assertIs<AirStringsError.SignatureVerificationFailed>(error)
    }

    @Test
    @DisplayName("signature with mixed text and icu entries verifies")
    fun signatureWithMixedFormatsVerifies() {
        val keyPair = generateKeyPair()
        val bundle = makeSignedBundle(
            strings = mapOf(
                "greeting" to textEntry("Hello"),
                "items" to StringEntry("{count, plural, one {# item} other {# items}}", StringFormat.ICU),
            ),
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
        )

        val verifier = BundleVerifier(
            publicKeys = listOf(keyPair.publicKeyBase64),
        )

        assertDoesNotThrow { verifier.verify(bundle) }
    }

    @Test
    @DisplayName("changing format from text to icu fails verification")
    fun changingFormatFailsVerification() {
        val keyPair = generateKeyPair()
        val original = makeSignedBundle(
            strings = mapOf("key" to textEntry("value")),
            privateKey = keyPair.privateKey,
            publicKey = keyPair.publicKey,
        )

        val tampered = original.copy(
            strings = mapOf("key" to StringEntry("value", StringFormat.ICU)),
        )

        val verifier = BundleVerifier(
            publicKeys = listOf(keyPair.publicKeyBase64),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(tampered) }
        assertIs<AirStringsError.SignatureVerificationFailed>(error)
    }

    @Test
    @DisplayName("invalid base64 key_id throws InvalidKeyIdEncoding")
    fun invalidBase64KeyIdThrows() {
        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-02-25T14:30:00Z",
            keyId = "not-valid-base64!!!",
            signature = "",
            strings = mapOf("hello" to textEntry("Hello")),
        )

        val verifier = BundleVerifier(
            publicKeys = listOf("not-valid-base64!!!"),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(bundle) }
        assertIs<AirStringsError.InvalidKeyIdEncoding>(error)
    }

    @Test
    @DisplayName("key_id that decodes to wrong size throws InvalidKeyIdEncoding")
    fun wrongSizeKeyIdThrows() {
        val shortKeyBase64 = Base64.getEncoder().encodeToString(ByteArray(16))

        val bundle = StringBundle(
            formatVersion = 1,
            projectId = "proj_test12345678",
            locale = "en",
            revision = 1,
            createdAt = "2026-02-25T14:30:00Z",
            keyId = shortKeyBase64,
            signature = "",
            strings = mapOf("hello" to textEntry("Hello")),
        )

        val verifier = BundleVerifier(
            publicKeys = listOf(shortKeyBase64),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(bundle) }
        assertIs<AirStringsError.InvalidKeyIdEncoding>(error)
    }
}
