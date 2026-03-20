package com.airstrings.sdk.security

import com.airstrings.sdk.AirStringsError
import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.StringBundle
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Verifies Ed25519 signatures on string bundles using BouncyCastle's lightweight API.
 *
 * [publicKeys] is a list of base64-encoded Ed25519 public keys (standard encoding, 44 chars each).
 * The bundle's `key_id` is the base64 encoding of the signing key. Verification checks that
 * `key_id` is in the configured list, then base64-decodes it to obtain the raw key bytes.
 *
 * Verification order (per contract):
 * 1. Check key_id ∈ publicKeys -> unknown key = hard error
 * 2. Base64-decode key_id -> invalid encoding = hard error
 * 3. Build canonical signed content
 * 4. Verify Ed25519 signature -> failure = hard error
 * 5. Check format_version -> unknown version = hard error
 */
internal class BundleVerifier(
    private val publicKeys: List<String>,
) {

    @Throws(AirStringsError::class)
    fun verify(bundle: StringBundle) {
        if (bundle.keyId !in publicKeys) {
            throw AirStringsError.UnknownKeyId(bundle.keyId)
        }

        val keyData = try {
            java.util.Base64.getDecoder().decode(bundle.keyId)
        } catch (_: IllegalArgumentException) {
            throw AirStringsError.InvalidKeyIdEncoding(bundle.keyId)
        }

        if (keyData.size != 32) {
            throw AirStringsError.InvalidKeyIdEncoding(bundle.keyId)
        }

        val canonicalBytes = CanonicalJson.signedContent(bundle)

        val signatureBytes = Base64Url.decode(bundle.signature)
        if (signatureBytes == null || signatureBytes.size != 64) {
            throw AirStringsError.InvalidSignatureEncoding()
        }

        val publicKey = Ed25519PublicKeyParameters(keyData, 0)
        val signer = Ed25519Signer()
        signer.init(false, publicKey)
        signer.update(canonicalBytes, 0, canonicalBytes.size)

        if (!signer.verifySignature(signatureBytes)) {
            throw AirStringsError.SignatureVerificationFailed()
        }

        if (bundle.formatVersion != 1) {
            throw AirStringsError.UnsupportedFormatVersion(bundle.formatVersion)
        }
    }
}
