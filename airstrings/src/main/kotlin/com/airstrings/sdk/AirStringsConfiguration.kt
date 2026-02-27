package com.airstrings.sdk

/**
 * Configuration for the AirStrings SDK.
 *
 * Not a data class because it contains [ByteArray] — data class would generate
 * broken `equals()`/`hashCode()` based on array reference identity.
 * Custom content-based `equals()`/`hashCode()` are implemented manually.
 *
 * All [publicKeys] byte arrays are defensively copied on construction.
 * The caller cannot mutate key material after creating a config.
 */
public class AirStringsConfiguration(
    public val projectId: String,
    publicKeys: Map<String, ByteArray>,
    public val locale: AirStringsLocale = AirStringsLocale.System,
    public val baseUrl: String = "https://cdn.airstrings.com",
) {

    /** Deep copy — each ByteArray is cloned. */
    public val publicKeys: Map<String, ByteArray> = publicKeys.mapValues { (_, v) -> v.copyOf() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AirStringsConfiguration) return false
        if (projectId != other.projectId) return false
        if (locale != other.locale) return false
        if (baseUrl != other.baseUrl) return false
        if (publicKeys.size != other.publicKeys.size) return false
        for ((key, value) in publicKeys) {
            val otherValue = other.publicKeys[key] ?: return false
            if (!value.contentEquals(otherValue)) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = projectId.hashCode()
        result = 31 * result + locale.hashCode()
        result = 31 * result + baseUrl.hashCode()
        for ((key, value) in publicKeys) {
            result = 31 * result + key.hashCode()
            result = 31 * result + value.contentHashCode()
        }
        return result
    }
}
