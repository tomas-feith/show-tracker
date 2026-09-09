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
import androidx.compose.material.icons.filled.ExpandMore
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
import com.showtracker.app.domain.describeReason
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.components.Divider
import com.showtracker.app.ui.components.PreviewSheet
import com.showtracker.app.ui.components.ResultRow
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.Danger
import com.showtracker.app.ui.theme.StateNew
import com.showtracker.app.ui.theme.Surface
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted

/**
 * Three ways in to a show the user does not already follow.
 *
 * "For you" is built from the library, one TMDB recommendation list per followed show,
 * ranked by how many of them agree - see `rankRecommendations`. "Favourites" is the same
 * question asked of the starred shows alone, which is the only way to ask it: ranking by
 * agreement means a large library outvotes a handful of favourites every time. "Trending"
 * is TMDB's global weekly list and has nothing to do with the library, which is exactly why
 * it is a separate tab rather than mixed in: a suggestion that claims to be about your
 * shows and is not would make the whole screen untrustworthy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    libraryViewModel: LibraryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()

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
                    // Two buttons, because they answer different questions and one button
                    // doing both read as a list being re-rolled at random on every tap.
                    // "Show more" walks the ranked pool and costs nothing; refresh re-asks
                    // TMDB and starts again from the top.
                    IconButton(
                        onClick = { viewModel.showMore() },
                        enabled = state.moreSuggestions,
                    ) {
                        Icon(
                            Icons.Default.ExpandMore,
                            contentDescription = "Show more suggestions",
                            tint = if (state.moreSuggestions) TextMuted else TextFaint,
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
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
            Tabs(state.tab, viewModel::selectTab)

            when (state.tab) {
                DiscoverTab.FOR_YOU -> {
                    Suggestions(
                        data = state.forYou,
                        tracked = tracked,
                        viewModel = viewModel,
                        emptyTitle = "Nothing to suggest yet",
                        emptyBody =
                            "Follow a few shows and TMDB's recommendations for them will " +
                                "be pooled here.",
                    )
                }

                DiscoverTab.FAVOURITES -> {
                    Suggestions(
                        data = state.favourites,
                        tracked = tracked,
                        viewModel = viewModel,
                        emptyTitle = "No favourites yet",
                        emptyBody =
                            "Star a show from its own screen, and suggestions built from " +
                                "your starred shows alone will appear here.",
                    )
                }

                DiscoverTab.TRENDING -> {
                    TabBody(
                        data = state.trending,
                        onDismissError = viewModel::dismissError,
                        emptyTitle = "Nothing trending",
                        emptyBody = "TMDB returned no trending shows for this week.",
                        key = { it.id },
                    ) { result ->
                        ResultRow(
                            name = result.name,
                            posterPath = result.posterPath,
                            subtitle = result.firstAirDate?.take(4) ?: "Date unknown",
                            tracked = result.id in tracked,
                            onClick = { viewModel.openPreview(result) },
                            voteAverage = result.voteAverage,
                            voteCount = result.voteCount,
                        )
                    }
                }
            }
        }
    }

    preview?.let { showing ->
        PreviewSheet(
            preview = showing,
            alreadyFollowing = showing.id in tracked,
            onFollow = { seenUpTo ->
                libraryViewModel.addShow(showing.id, seenUpTo, viewModel::showError)
                // Dropped from the pool rather than left showing a tick: the tab answers
                // "what next", and something now being followed is no longer an answer.
                viewModel.onFollowed(showing.id)
                viewModel.closePreview()
            },
            onDismissShow = { viewModel.dismiss(showing.id) },
            onClose = viewModel::closePreview,
        )
    }
}

@Composable
private fun Tabs(
    selected: DiscoverTab,
    onSelect: (DiscoverTab) -> Unit,
) {
    TabRow(
        selectedTabIndex = selected.ordinal,
        containerColor = Surface,
        contentColor = Accent,
    ) {
        Tab(
            selected = selected == DiscoverTab.FOR_YOU,
            onClick = { onSelect(DiscoverTab.FOR_YOU) },
            text = { Text("For you") },
            selectedContentColor = Accent,
            unselectedContentColor = TextMuted,
        )
        Tab(
            selected = selected == DiscoverTab.FAVOURITES,
            onClick = { onSelect(DiscoverTab.FAVOURITES) },
            text = { Text("Favourites") },
            selectedContentColor = Accent,
            unselectedContentColor = TextMuted,
        )
        Tab(
            selected = selected == DiscoverTab.TRENDING,
            onClick = { onSelect(DiscoverTab.TRENDING) },
            text = { Text("Trending") },
            selectedContentColor = Accent,
            unselectedContentColor = TextMuted,
        )
    }
}

/**
 * A ranked suggestion list, which is what both seeded tabs are.
 *
 * One function rather than two identical blocks: the tabs differ only in what they are
 * built from and in what they say when they are empty, and that difference belongs in the
 * two call sites rather than in two copies of a row.
 */
@Composable
private fun Suggestions(
    data: TabData<Candidate>,
    tracked: Set<Int>,
    viewModel: DiscoverViewModel,
    emptyTitle: String,
    emptyBody: String,
) {
    TabBody(
        data = data,
        onDismissError = viewModel::dismissError,
        emptyTitle = emptyTitle,
        emptyBody = emptyBody,
        key = { it.show.id },
    ) { candidate ->
        ResultRow(
            name = candidate.show.name,
            posterPath = candidate.show.posterPath,
            subtitle = describeReason(candidate.becauseOf),
            tracked = candidate.show.id in tracked,
            onClick = { viewModel.openPreview(candidate.show) },
            voteAverage = candidate.show.voteAverage,
            voteCount = candidate.show.voteCount,
        )
    }
}

/**
 * The shared frame around any tab: spinner, error, note, empty state, or the list.
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
