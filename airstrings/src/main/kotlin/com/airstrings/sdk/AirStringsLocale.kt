package com.airstrings.sdk

public sealed class AirStringsLocale {
    /** Uses the device's current locale, mapped to a BCP 47 language tag. */
    public data object System : AirStringsLocale()

    /** Always uses the specified BCP 47 locale regardless of device settings. */
    public data class Fixed(public val bcp47: String) : AirStringsLocale()

    internal fun resolved(): String {
        return when (this) {
            is System -> java.util.Locale.getDefault().language
            is Fixed -> bcp47
        }
    }
}
