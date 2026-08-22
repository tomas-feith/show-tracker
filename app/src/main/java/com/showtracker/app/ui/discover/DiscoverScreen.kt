package com.showtracker.app.ui.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.showtracker.app.domain.Candidate
import com.showtracker.app.domain.SearchResult
import com.showtracker.app.domain.describeReason
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.components.Divider
import com.showtracker.app.ui.components.ResultRow
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.Danger
import com.showtracker.app.ui.theme.StateNew
import com.showtracker.app.ui.theme.Surface
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted

/**
 * Two ways in to a show the user does not already follow.
 *
 * "For you" is built from the library, one TMDB recommendation list per followed show,
 * ranked by how many of them agree - see `rankRecommendations`. "Trending" is TMDB's
 * global weekly list and has nothing to do with the library, which is exactly why it is a
 * separate tab rather than mixed in: a suggestion that claims to be about your shows and
 * is not would make the whole screen untrustworthy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    libraryViewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Read from the library's own flow rather than tracked in the discover state. An add is
    // asynchronous and can fail; marking a row followed the moment it was tapped would tick
    // and disable it even when nothing was saved, with no way back but a refresh. Room
    // emits on success only, so the tick means what it says.
    val library by libraryViewModel.state.collectAsStateWithLifecycle()
    val tracked = library.shows.map { it.id }.toSet()

    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Discover") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextMuted,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.load(force = true) }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh suggestions",
                            tint = TextMuted,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = Surface,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                    ),
            )
        },
    ) { insets ->
        Column(Modifier.padding(insets).fillMaxSize()) {
            TabRow(
                selectedTabIndex = state.tab.ordinal,
                containerColor = Surface,
                contentColor = Accent,
            ) {
                Tab(
                    selected = state.tab == DiscoverTab.FOR_YOU,
                    onClick = { viewModel.selectTab(DiscoverTab.FOR_YOU) },
                    text = { Text("For you") },
                    selectedContentColor = Accent,
                    unselectedContentColor = TextMuted,
                )
                Tab(
                    selected = state.tab == DiscoverTab.TRENDING,
                    onClick = { viewModel.selectTab(DiscoverTab.TRENDING) },
                    text = { Text("Trending") },
                    selectedContentColor = Accent,
                    unselectedContentColor = TextMuted,
                )
            }

            when (state.tab) {
                DiscoverTab.FOR_YOU -> {
                    TabBody(
                        data = state.forYou,
                        onDismissError = viewModel::dismissError,
                        emptyTitle = "Nothing to suggest yet",
                        emptyBody =
                            "Follow a few shows and TMDB's recommendations for them will " +
                                "be pooled here.",
                        key = { it.show.id },
                    ) { candidate ->
                        SuggestionRow(candidate, tracked, viewModel, libraryViewModel)
                    }
                }

                DiscoverTab.TRENDING -> {
                    TabBody(
                        data = state.trending,
                        onDismissError = viewModel::dismissError,
                        emptyTitle = "Nothing trending",
                        emptyBody = "TMDB returned no trending shows for this week.",
                        key = { it.id },
                    ) { result ->
                        TrendingRow(result, tracked, viewModel, libraryViewModel)
                    }
                }
            }
        }
    }
}

/**
 * The shared frame around either tab: spinner, error, note, empty state, or the list.
 *
 * Both tabs load the same way and fail the same way, so the states they can be in are
 * worth writing once - only the row differs, which is what [row] is for.
 */
@Composable
private fun <T> TabBody(
    data: TabData<T>,
    onDismissError: () -> Unit,
    emptyTitle: String,
    emptyBody: String,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        data.error?.let { message ->
            Text(
                text = message,
                color = Danger,
                style = MaterialTheme.typography.bodySmall,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onDismissError() }
                        .padding(16.dp),
            )
        }

        data.note?.let { message ->
            Text(
                text = message,
                color = StateNew,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        when {
            data.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }

            // Only once a load has actually finished. Before that the tab is blank rather
            // than claiming there is nothing to show.
            data.loaded && data.items.isEmpty() -> {
                Empty(emptyTitle, emptyBody)
            }

            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(data.items, key = key) { item ->
                        row(item)
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    candidate: Candidate,
    tracked: Set<Int>,
    viewModel: DiscoverViewModel,
    libraryViewModel: LibraryViewModel,
) {
    ResultRow(
        name = candidate.show.name,
        posterPath = candidate.show.posterPath,
        subtitle = describeReason(candidate.becauseOf),
        tracked = candidate.show.id in tracked,
        onClick = { libraryViewModel.addShow(candidate.show.id, viewModel::showError) },
    )
}

@Composable
private fun TrendingRow(
    result: SearchResult,
    tracked: Set<Int>,
    viewModel: DiscoverViewModel,
    libraryViewModel: LibraryViewModel,
) {
    ResultRow(
        name = result.name,
        posterPath = result.posterPath,
        subtitle = result.firstAirDate?.take(4) ?: "Date unknown",
        tracked = result.id in tracked,
        onClick = { libraryViewModel.addShow(result.id, viewModel::showError) },
    )
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
