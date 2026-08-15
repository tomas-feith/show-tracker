package com.showtracker.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.domain.sortLibrary
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.components.Divider
import com.showtracker.app.ui.components.RowSemantics
import com.showtracker.app.ui.components.ShowRow
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.Danger
import com.showtracker.app.ui.theme.Surface
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenShow: (Int) -> Unit,
    onAddShow: () -> Unit,
    onOpenSettings: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // Sorted here rather than in the query: the order depends on today's date and on
    // derived state, neither of which SQL knows about.
    val ordered = sortLibrary(state.shows, today)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("My Shows") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Surface,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                actions = {
                    if (refreshing) {
                        CircularProgressIndicator(
                            Modifier.padding(end = 16.dp).size(20.dp),
                            strokeWidth = 2.dp,
                            color = Accent,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextMuted,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.apiKey != null) {
                FloatingActionButton(onClick = onAddShow, containerColor = Accent) {
                    Icon(Icons.Default.Add, contentDescription = "Add a show")
                }
            }
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            error?.let { message ->
                Text(
                    text = message,
                    color = Danger,
                    style = MaterialTheme.typography.bodySmall,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.dismissError() }
                            .padding(16.dp),
                )
            }

            when {
                !state.ready -> {
                    // Deliberately blank until the first database and settings emission
                    // arrives. A flash of "no TMDB key yet" before the real state lands
                    // reads as data loss.
                }

                state.apiKey == null -> {
                    Empty(
                        title = "No TMDB key yet",
                        body = "Open Settings and paste a TMDB key to start following shows.",
                    )
                }

                ordered.isEmpty() -> {
                    Empty(
                        title = "Nothing followed yet",
                        body = "Tap the plus button to find a show.",
                    )
                }

                else -> {
                    Library(ordered, today, onOpenShow)
                }
            }
        }
    }
}

@Composable
private fun Library(
    shows: List<TrackedShow>,
    today: LocalDate,
    onOpenShow: (Int) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(shows, key = { it.id }) { show ->
            RowSemantics(show, today) {
                Row(Modifier.clickable { onOpenShow(show.id) }) {
                    ShowRow(show, today)
                }
            }
            Divider()
        }
    }
}

@Composable
private fun Empty(
    title: String,
    body: String,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextFaint,
                textAlign = TextAlign.Center,
            )
        }
    }
}
