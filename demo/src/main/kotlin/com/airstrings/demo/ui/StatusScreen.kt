package com.airstrings.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airstrings.demo.DemoConfig
import com.airstrings.sdk.compose.LocalAirStrings
import kotlinx.coroutines.launch

@Composable
fun StatusScreen(modifier: Modifier = Modifier) {
    val airStrings = LocalAirStrings.current
    val isReady by airStrings.isReady.collectAsStateWithLifecycle()
    val currentLocale by airStrings.currentLocale.collectAsStateWithLifecycle()
    val revision by airStrings.revision.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Status indicator
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isReady) "\u2705" else "\u23F3",
                modifier = Modifier.size(24.dp),
            )
            Column {
                Text(
                    text = if (isReady) "Ready" else "Loading...",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Locale: $currentLocale \u2022 Revision: $revision",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        HorizontalDivider()

        // Configuration section
        Text(
            text = "Configuration",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        ConfigRow(label = "Project", value = DemoConfig.PROJECT_ID)
        ConfigRow(label = "Key ID", value = DemoConfig.KEY_ID)
        ConfigRow(label = "Base URL", value = DemoConfig.BASE_URL)

        HorizontalDivider()

        // Refresh button
        Button(
            onClick = { scope.launch { airStrings.refresh() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("\u21BB  Refresh Now")
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
