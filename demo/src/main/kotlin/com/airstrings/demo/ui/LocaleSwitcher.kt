package com.airstrings.demo.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airstrings.demo.DemoConfig
import com.airstrings.sdk.compose.LocalAirStrings
import kotlinx.coroutines.launch

@Composable
fun LocaleSwitcher(modifier: Modifier = Modifier) {
    val airStrings = LocalAirStrings.current
    val currentLocale by airStrings.currentLocale.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SingleChoiceSegmentedButtonRow(modifier = modifier.width(200.dp)) {
        DemoConfig.AVAILABLE_LOCALES.forEachIndexed { index, locale ->
            SegmentedButton(
                selected = currentLocale == locale,
                onClick = {
                    scope.launch { airStrings.setLocale(locale) }
                },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = DemoConfig.AVAILABLE_LOCALES.size,
                ),
            ) {
                Text(locale.uppercase())
            }
        }
    }
}
