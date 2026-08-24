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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.showtracker.app.domain.LibraryFilters
import com.showtracker.app.domain.LibrarySort
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.domain.applyFilters
import com.showtracker.app.domain.filterLibrary
import com.showtracker.app.domain.libraryGenres
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
    onDiscover: () -> Unit,
    onOpenSettings: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // Kept in the screen rather than the view model: it is a way of looking at the library,
    // not part of it, and it should not survive being navigated away from and back to.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }

    // Which control is open. Both belong to the screen: a menu left open across a trip to a
    // show and back would reopen over a list the user has stopped thinking about.
    var sorting by rememberSaveable { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }

    val filters by viewModel.filters.collectAsStateWithLifecycle()

    // Narrowed before sorting, so the order of what survives is the order it would have had
    // in the full library - a result that reshuffled itself would be harder to read, not
    // easier. The search box runs inside the filters rather than beside them: it answers
    // "where is this show", which is a question about what is on screen now.
    // Remembered rather than recomputed on every recomposition: typing in the search box
    // recomposes on each keystroke, and all three passes walk every season list.
    val filtered =
        remember(state.shows, filters, today) { applyFilters(state.shows, filters, today) }
    val visible = remember(filtered, query) { filterLibrary(filtered, query) }
    val ordered =
        remember(visible, state.sort, today) { sortLibrary(visible, today, state.sort) }

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
                    LibraryActions(
                        bar =
                            LibraryBar(
                                refreshing = refreshing,
                                hasShows = state.shows.isNotEmpty(),
                                hasKey = state.apiKey != null,
                                sort = state.sort,
                                filtersActive = filters.active,
                                sorting = sorting,
                                searching = searching,
                            ),
                        onBar =
                            LibraryBarActions(
                                onSortingChange = { sorting = it },
                                onPickSort = viewModel::setSort,
                                onOpenFilters = { filtering = true },
                                onSearchingChange = { open ->
                                    searching = open
                                    // Closing clears, so the library is never left
                                    // silently filtered behind a hidden box.
                                    if (!open) query = ""
                                },
                                onDiscover = onDiscover,
                                onOpenSettings = onOpenSettings,
                            ),
                    )
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
            if (searching) {
                SearchField(query, onQueryChange = { query = it })
            }

            // The disclosure. A filtered library that does not say it is filtered is a
            // library with shows missing from it, and the way back has to be one tap from
            // wherever the surprise happens.
            if (filters.active) {
                FilterSummary(
                    // Before the search box, which the line does not mention and its
                    // button does not clear: counting it would promise a number that
                    // tapping "Clear filters" does not restore.
                    showing = filtered.size,
                    total = state.shows.size,
                    onClear = { viewModel.setFilters(LibraryFilters()) },
                )
            }

            error?.let { message ->
                ErrorLine(message, onDismiss = viewModel::dismissError)
            }

            if (ordered.isEmpty()) {
                LibraryPlaceholder(
                    ready = state.ready,
                    hasKey = state.apiKey != null,
                    query = query,
                    filtersActive = filters.active,
                )
            } else {
                Library(ordered, today, onOpenShow)
            }
        }
    }

    if (filtering) {
        FilterSheet(
            filters = filters,
            // Union with what is already selected: unfollowing the last comedy show while
            // filtering by Comedy would otherwise remove the only control that could
            // untoggle it, leaving an empty list and no way back but "Clear all".
            genres = (libraryGenres(state.shows) + filters.genres).distinct().sorted(),
            onChange = viewModel::setFilters,
            onClear = {
                viewModel.setFilters(LibraryFilters())
                filtering = false
            },
            onClose = { filtering = false },
        )
    }
}

/** What the top bar draws. */
private data class LibraryBar(
    val refreshing: Boolean,
    val hasShows: Boolean,
    val hasKey: Boolean,
    val sort: LibrarySort,
    val filtersActive: Boolean,
    val sorting: Boolean,
    val searching: Boolean,
)

/**
 * What its buttons do. Separate from [LibraryBar] so what is state and what is a callback
 * stays obvious at the call site.
 */
private data class LibraryBarActions(
    val onSortingChange: (Boolean) -> Unit,
    val onPickSort: (LibrarySort) -> Unit,
    val onOpenFilters: () -> Unit,
    val onSearchingChange: (Boolean) -> Unit,
    val onDiscover: () -> Unit,
    val onOpenSettings: () -> Unit,
)

/**
 * The top bar's buttons.
 *
 * Pulled out of [LibraryScreen] because it had grown past what one function should hold,
 * not because it is reused. Every piece of state it needs is passed in, so the bar has no
 * opinion about where the library comes from.
 */
@Composable
private fun LibraryActions(
    bar: LibraryBar,
    onBar: LibraryBarActions,
) {
    if (bar.refreshing) {
        CircularProgressIndicator(
            Modifier.padding(end = 16.dp).size(20.dp),
            strokeWidth = 2.dp,
            color = Accent,
        )
    }
    if (bar.hasShows) {
        Box {
            IconButton(onClick = { onBar.onSortingChange(true) }) {
                Icon(
                    Icons.AutoMirrored.Filled.Sort,
                    contentDescription = "Sort your shows",
                    tint = TextMuted,
                )
            }
            SortMenu(
                expanded = bar.sorting,
                current = bar.sort,
                onPick = onBar.onPickSort,
                onDismiss = { onBar.onSortingChange(false) },
            )
        }
        IconButton(onClick = onBar.onOpenFilters) {
            Icon(
                Icons.Default.FilterList,
                contentDescription = "Filter your shows",
                // Lit while anything is hidden, so the one control that can
                // empty the screen says so from the bar itself.
                tint = if (bar.filtersActive) Accent else TextMuted,
            )
        }
        IconButton(onClick = { onBar.onSearchingChange(!bar.searching) }) {
            Icon(
                if (bar.searching) Icons.Default.Close else Icons.Default.Search,
                contentDescription =
                    if (bar.searching) "Close search" else "Search your shows",
                tint = TextMuted,
            )
        }
    }
    // Only with a key: every destination behind it is a TMDB call, and an
    // empty screen saying so is worse than not offering the trip.
    if (bar.hasKey) {
        IconButton(onClick = onBar.onDiscover) {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = "Discover shows",
                tint = TextMuted,
            )
        }
    }
    IconButton(onClick = onBar.onOpenSettings) {
        Icon(
            Icons.Default.Settings,
            contentDescription = "Settings",
            tint = TextMuted,
        )
    }
}

/**
 * What stands in for the list when there is nothing to draw.
 *
 * Four different nothings, and telling them apart is the whole job: a library that has not
 * loaded, one with no key, one narrowed to nothing by a search or a filter, and one that is
 * genuinely empty. Before the filters existed the last case could be assumed, and a user
 * with thirty shows who picked "9.0+" was told to tap the plus button and go find a show.
 */
@Composable
private fun LibraryPlaceholder(
    ready: Boolean,
    hasKey: Boolean,
    query: String,
    filtersActive: Boolean,
) {
    when {
        // Deliberately blank until the first database and settings emission arrives. A
        // flash of "no TMDB key yet" before the real state lands reads as data loss.
        !ready -> {
            Unit
        }

        !hasKey -> {
            Empty(
                title = "No TMDB key yet",
                body = "Open Settings and paste a TMDB key to start following shows.",
            )
        }

        query.isNotBlank() -> {
            Empty(
                title = "No shows match",
                body =
                    if (filtersActive) {
                        // Otherwise the app asserts the show is not in the library, when it
                        // may be sitting there behind a filter.
                        "Nothing called \"${query.trim()}\" matches your filters."
                    } else {
                        "Nothing in your library is called \"${query.trim()}\"."
                    },
            )
        }

        filtersActive -> {
            Empty(
                title = "No shows match these filters",
                body = "Clear them, or widen them, to see your library again.",
            )
        }

        else -> {
            Empty(
                title = "Nothing followed yet",
                body = "Tap the plus button to find a show.",
            )
        }
    }
}

/**
 * The disclosure line, shown only while something is hidden.
 *
 * A filtered library that does not say it is filtered is a library with shows missing from
 * it, so the count and the way back are on screen together, above the list rather than
 * buried in the sheet that set them.
 */
@Composable
private fun FilterSummary(
    showing: Int,
    total: Int,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Showing $showing of $total",
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
        TextButton(onClick = onClear) { Text("Clear filters", color = Accent) }
    }
}

/** The refresh failure, tappable to dismiss. */
@Composable
private fun ErrorLine(
    message: String,
    onDismiss: () -> Unit,
) {
    Text(
        text = message,
        color = Danger,
        style = MaterialTheme.typography.bodySmall,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onDismiss)
                .padding(16.dp),
    )
}

/**
 * The filter box, focused as it appears so the keyboard is already up.
 *
 * Below the bar rather than replacing the title in it: it matches the box on the add-a-show
 * screen, which is the other place in the app someone types a show's name.
 */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestFocus() }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search your shows", color = TextFaint) },
        singleLine = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .focusRequester(focus),
    )
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
