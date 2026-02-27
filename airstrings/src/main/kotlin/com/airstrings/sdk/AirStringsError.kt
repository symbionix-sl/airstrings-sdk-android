package com.airstrings.sdk

public sealed class AirStringsError(override val message: String) : Exception(message) {
    public class UnknownKeyId(public val keyId: String) : AirStringsError("Unknown key_id: $keyId")
    public class SignatureVerificationFailed : AirStringsError("Ed25519 signature verification failed")
    public class UnsupportedFormatVersion(public val version: Int) : AirStringsError("Unsupported format_version: $version")
    public class BundleDecodingFailed(public val reason: String) : AirStringsError("Bundle decoding failed: $reason")
    public class InvalidSignatureEncoding : AirStringsError("Signature is not valid base64url or not 64 bytes")
}
