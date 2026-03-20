package com.airstrings.demo.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

@Composable
fun DemoApp() {
    MaterialTheme {
        var selectedTab by rememberSaveable { mutableIntStateOf(0) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Text("\u201C\u201D") },
                        label = { Text("Strings") },
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Text("\u2139") },
                        label = { Text("Status") },
                    )
                }
            },
        ) { padding ->
            when (selectedTab) {
                0 -> StringListScreen(modifier = Modifier.padding(padding))
                1 -> StatusScreen(modifier = Modifier.padding(padding))
            }
        }
    }
}
