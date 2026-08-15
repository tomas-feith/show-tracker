package com.showtracker.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.showtracker.app.ui.theme.ShowTrackerTheme
import com.showtracker.app.ui.theme.TextMuted

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ShowTrackerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { insets ->
                    Placeholder(Modifier.padding(insets))
                }
            }
        }
    }
}

/**
 * Stands in until the library screen lands in a later phase. Present so the skeleton is
 * something that can actually be installed and looked at, rather than a project that
 * merely compiles.
 */
@Composable
private fun Placeholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Show Tracker",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = "Kotlin rebuild in progress",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderPreview() {
    ShowTrackerTheme { Placeholder() }
}
