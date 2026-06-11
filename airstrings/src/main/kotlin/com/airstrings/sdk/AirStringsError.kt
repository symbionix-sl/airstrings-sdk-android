package com.airstrings.sdk

public sealed class AirStringsError(override val message: String) : Exception(message) {
    public class UnknownKeyId(public val keyId: String) : AirStringsError("Unknown key_id: $keyId")
    public class SignatureVerificationFailed : AirStringsError("Ed25519 signature verification failed")
    public class UnsupportedFormatVersion(public val version: Int) : AirStringsError("Unsupported format_version: $version")
    public class BundleDecodingFailed(public val reason: String) : AirStringsError("Bundle decoding failed: $reason")
    public class InvalidSignatureEncoding : AirStringsError("Signature is not valid base64url or not 64 bytes")
    public class InvalidKeyIdEncoding(public val keyId: String) : AirStringsError("key_id is not valid base64 or not 32 bytes: $keyId")
    public class SeedProjectMismatch(public val expected: String, public val actual: String) : AirStringsError("Seed bundle project_id mismatch: expected $expected, got $actual")
    public class SeedLocaleMismatch(public val expected: String, public val actual: String) : AirStringsError("Seed bundle locale mismatch: expected $expected, got $actual")
}
