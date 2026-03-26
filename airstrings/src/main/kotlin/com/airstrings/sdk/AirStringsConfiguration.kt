package com.airstrings.sdk

/**
 * Configuration for the AirStrings SDK.
 *
 * [publicKeys] is a list of base64-encoded Ed25519 public keys.
 * The bundle's `key_id` field is the base64 encoding of the signing key —
 * verification checks that `key_id` is in this list, then decodes it to
 * obtain the raw public key bytes.
 */
public data class AirStringsConfiguration(
    public val organizationId: String,
    public val projectId: String,
    public val environmentId: String,
    public val publicKeys: List<String>,
    public val locale: AirStringsLocale = AirStringsLocale.System,
)
