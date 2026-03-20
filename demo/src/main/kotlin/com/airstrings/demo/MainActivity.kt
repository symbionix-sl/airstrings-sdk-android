package com.airstrings.demo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import com.airstrings.sdk.AirStrings
import com.airstrings.sdk.AirStringsConfiguration
import com.airstrings.sdk.AirStringsLocale
import com.airstrings.sdk.compose.LocalAirStrings
import com.airstrings.demo.ui.DemoApp

class MainActivity : ComponentActivity() {

    private lateinit var airStrings: AirStrings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        airStrings = AirStrings.create(
            context = this,
            configuration = AirStringsConfiguration(
                projectId = DemoConfig.PROJECT_ID,
                publicKeys = mapOf(DemoConfig.KEY_ID to DemoConfig.publicKeyData),
                locale = AirStringsLocale.Fixed("en"),
            ),
        )

        setContent {
            CompositionLocalProvider(LocalAirStrings provides airStrings) {
                DemoApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::airStrings.isInitialized) {
            airStrings.close()
        }
    }
}
