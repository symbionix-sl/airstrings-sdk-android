package com.airstrings.sdk.security

import com.airstrings.sdk.AirStringsError
import com.airstrings.sdk.models.CanonicalJson
import com.airstrings.sdk.models.StringBundle
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Verifies Ed25519 signatures on string bundles using BouncyCastle's lightweight API.
 *
 * Verification order (per contract):
 * 1. Look up key_id -> unknown key = hard error
 * 2. Build canonical signed content
 * 3. Verify Ed25519 signature -> failure = hard error
 * 4. Check format_version -> unknown version = hard error
 */
internal class BundleVerifier(
    private val publicKeys: Map<String, ByteArray>,
) {

    @Throws(AirStringsError::class)
    fun verify(bundle: StringBundle) {
        val keyData = publicKeys[bundle.keyId]
            ?: throw AirStringsError.UnknownKeyId(bundle.keyId)

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
