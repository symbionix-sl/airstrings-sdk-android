package com.airstrings.demo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.airstrings.sdk.compose.LocalAirStrings
import kotlinx.coroutines.launch

private val demoKeys = listOf(
    "greeting",
    "farewell",
    "app.title",
    "settings.theme",
    "settings.language",
    "onboarding.welcome",
)

private data class IcuDemo(
    val key: String,
    val args: Map<String, Any>,
    val label: String,
)

private val icuDemos = listOf(
    IcuDemo("items.count", mapOf("count" to 1), "count=1"),
    IcuDemo("items.count", mapOf("count" to 5), "count=5"),
    IcuDemo("items.count", mapOf("count" to 0), "count=0"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StringListScreen(modifier: Modifier = Modifier) {
    val airStrings = LocalAirStrings.current
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        // Locale switcher centered at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            LocaleSwitcher()
        }

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    airStrings.refresh()
                    isRefreshing = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                // Plain strings section
                item {
                    SectionHeader("Strings")
                }

                items(demoKeys) { key ->
                    val value = airStrings[key]
                    StringRow(key = key, value = value)
                }

                // ICU formatting section
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    SectionHeader("ICU Formatting")
                }

                items(icuDemos) { demo ->
                    val formatted = airStrings.format(demo.key, demo.args)
                    IcuRow(key = demo.key, argsLabel = demo.label, value = formatted)
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider()
}

@Composable
private fun StringRow(key: String, value: String) {
    val isFallback = value == key

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = key,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isFallback) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontStyle = if (isFallback) FontStyle.Italic else FontStyle.Normal,
            )
        }
        if (isFallback) {
            Text(
                text = "\u26A0",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun IcuRow(key: String, argsLabel: String, value: String) {
    val isFallback = value == key

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = "$key ($argsLabel)",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isFallback) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontStyle = if (isFallback) FontStyle.Italic else FontStyle.Normal,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}
