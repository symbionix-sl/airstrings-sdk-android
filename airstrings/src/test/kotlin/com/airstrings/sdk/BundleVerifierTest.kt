package com.airstrings.sdk

import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.StringBundle
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
    )

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

    private fun makeSignedBundle(
        formatVersion: Int = 1,
        projectId: String = "proj_test12345678",
        locale: String = "en",
        revision: Int = 1,
        createdAt: String = "2026-02-25T14:30:00Z",
        keyId: String = "key_test_01",
        strings: Map<String, String> = mapOf("hello" to "Hello World"),
        privateKey: Ed25519PrivateKeyParameters,
    ): StringBundle {
        val unsigned = StringBundle(
            formatVersion = formatVersion,
            projectId = projectId,
            locale = locale,
            revision = revision,
            createdAt = createdAt,
            keyId = keyId,
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
        val bundle = makeSignedBundle(privateKey = keyPair.privateKey)
        val verifier = BundleVerifier(
            publicKeys = mapOf("key_test_01" to keyPair.publicKey.encoded),
        )

        assertDoesNotThrow { verifier.verify(bundle) }
    }

    @Test
    @DisplayName("wrong key fails verification")
    fun wrongSignatureThrows() {
        val signingKey = generateKeyPair()
        val wrongKey = generateKeyPair()
        val bundle = makeSignedBundle(privateKey = signingKey.privateKey)
        val verifier = BundleVerifier(
            publicKeys = mapOf("key_test_01" to wrongKey.publicKey.encoded),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(bundle) }
        assertIs<AirStringsError.SignatureVerificationFailed>(error)
    }

    @Test
    @DisplayName("unknown key_id throws")
    fun unknownKeyIdThrows() {
        val keyPair = generateKeyPair()
        val bundle = makeSignedBundle(keyId = "key_unknown_99", privateKey = keyPair.privateKey)
        val verifier = BundleVerifier(
            publicKeys = mapOf("key_test_01" to keyPair.publicKey.encoded),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(bundle) }
        assertIs<AirStringsError.UnknownKeyId>(error)
        assertEquals("key_unknown_99", (error as AirStringsError.UnknownKeyId).keyId)
    }

    @Test
    @DisplayName("unsupported format_version throws")
    fun unsupportedFormatVersionThrows() {
        val keyPair = generateKeyPair()
        val bundle = makeSignedBundle(formatVersion = 99, privateKey = keyPair.privateKey)
        val verifier = BundleVerifier(
            publicKeys = mapOf("key_test_01" to keyPair.publicKey.encoded),
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
            keyId = "key_test_01",
            signature = "not-valid-base64url-!!@@##",
            strings = mapOf("hello" to "Hello"),
        )

        val verifier = BundleVerifier(
            publicKeys = mapOf("key_test_01" to keyPair.publicKey.encoded),
        )

        assertThrows<AirStringsError> { verifier.verify(bundle) }
    }

    @Test
    @DisplayName("signature with multiple strings verifies")
    fun signatureWithMultipleStrings() {
        val keyPair = generateKeyPair()
        val bundle = makeSignedBundle(
            strings = mapOf(
                "z.last" to "Last",
                "a.first" to "First",
                "m.middle" to "Middle",
            ),
            privateKey = keyPair.privateKey,
        )

        val verifier = BundleVerifier(
            publicKeys = mapOf("key_test_01" to keyPair.publicKey.encoded),
        )

        assertDoesNotThrow { verifier.verify(bundle) }
    }

    @Test
    @DisplayName("tampered strings fail verification")
    fun tamperedStringsFailVerification() {
        val keyPair = generateKeyPair()
        val original = makeSignedBundle(
            strings = mapOf("key" to "original"),
            privateKey = keyPair.privateKey,
        )

        val tampered = original.copy(strings = mapOf("key" to "tampered"))

        val verifier = BundleVerifier(
            publicKeys = mapOf("key_test_01" to keyPair.publicKey.encoded),
        )

        val error = assertThrows<AirStringsError> { verifier.verify(tampered) }
        assertIs<AirStringsError.SignatureVerificationFailed>(error)
    }
}
