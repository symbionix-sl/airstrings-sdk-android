package com.airstrings.sdk.storage

import android.content.res.AssetManager

internal fun interface SeedSource {
    fun load(locale: String): ByteArray?
}

internal class AssetSeedSource(
    private val assets: AssetManager,
    private val directory: String,
) : SeedSource {

    override fun load(locale: String): ByteArray? {
        return try {
            assets.open("${directory.trimEnd('/')}/$locale.json").use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }
}
