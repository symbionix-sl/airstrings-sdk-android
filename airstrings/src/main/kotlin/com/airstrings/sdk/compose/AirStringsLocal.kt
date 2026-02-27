package com.airstrings.sdk.compose

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.airstrings.sdk.AirStrings

/**
 * CompositionLocal for providing [AirStrings] to the Compose tree.
 *
 * Usage:
 * ```kotlin
 * CompositionLocalProvider(LocalAirStrings provides airStrings) {
 *     MyApp()
 * }
 *
 * @Composable
 * fun WelcomeScreen() {
 *     val airStrings = LocalAirStrings.current
 *     val strings by airStrings.strings.collectAsStateWithLifecycle()
 *     Text(strings["onboarding.title"] ?: "onboarding.title")
 * }
 * ```
 */
public val LocalAirStrings: ProvidableCompositionLocal<AirStrings> =
    staticCompositionLocalOf {
        error("No AirStrings instance provided. Wrap your composable tree with CompositionLocalProvider(LocalAirStrings provides airStrings).")
    }
