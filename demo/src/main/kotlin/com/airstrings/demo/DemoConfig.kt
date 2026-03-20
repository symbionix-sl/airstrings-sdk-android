package com.airstrings.demo

/**
 * Static configuration for the demo app.
 * The public key is generated into DemoConfig.generated.kt (gitignored).
 * Run `make config` before building.
 */
object DemoConfig {
    const val PROJECT_ID = "proj_demo00000001"

    /** MinIO endpoint — SDK builds: {baseURL}/v1/{projectId}/{locale}/bundle.json */
    const val BASE_URL = "http://10.0.2.127:9000/airstrings-bundles/bundles"

    /** Locales seeded by seed.sh. */
    val AVAILABLE_LOCALES = listOf("en", "fr", "es")

    /** Key ID matching the signing key registered in the DB. */
    const val KEY_ID = "key_dev_01"

    /** Ed25519 public key bytes — defined in DemoConfig.generated.kt. */
    val publicKeyData: ByteArray get() = generatedPublicKey
}
