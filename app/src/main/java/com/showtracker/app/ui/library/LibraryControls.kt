package com.showtracker.app.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.showtracker.app.domain.LibraryFilters
import com.showtracker.app.domain.LibrarySort
import com.showtracker.app.domain.RuntimeBand
import com.showtracker.app.domain.ScoreFloor
import com.showtracker.app.domain.StateGroup
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.Surface
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted

/**
 * The order menu.
 *
 * A menu rather than a row of chips: the orders are mutually exclusive and only one can be
 * true at a time, and six chips would take a line of the screen permanently to say
 * something the list itself already shows.
 */
@Composable
fun SortMenu(
    expanded: Boolean,
    current: LibrarySort,
    onPick: (LibrarySort) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = Surface) {
        LibrarySort.entries.forEach { sort ->
            DropdownMenuItem(
                text = {
                    Text(
                        sort.label,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = if (sort == current) FontWeight.SemiBold else null,
                    )
                },
                // The tick marks the current order rather than a radio button, matching the
                // "already following" tick the result rows use for the same idea.
                trailingIcon = {
                    if (sort == current) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Accent)
                    }
                },
                onClick = {
                    onPick(sort)
                    onDismiss()
                },
            )
        }
    }
}

/**
 * The filter sheet.
 *
 * A sheet rather than a menu because four axes do not fit one: the state and genre axes are
 * multi-select and the other two are not, and a nested menu would hide that difference. The
 * chips make it visible - a tapped chip stays lit, and tapping it again lets go.
 *
 * Every change is applied immediately rather than behind an "Apply" button. The list is
 * behind the sheet and the count line moves as the chips are tapped, so the effect of a
 * choice is visible while making it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    filters: LibraryFilters,
    genres: List<String>,
    onChange: (LibraryFilters) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Filter",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                if (filters.active) {
                    TextButton(onClick = onClear) { Text("Clear all", color = Accent) }
                }
            }

            // First, above the state chips: it is the one axis that is about the user's
            // own opinion rather than about what the show is doing, and it is the most
            // likely reason to open the sheet at all.
            Section("Favourites") {
                Chip("Starred only", filters.favouritesOnly) {
                    onChange(filters.copy(favouritesOnly = !filters.favouritesOnly))
                }
            }

            Section("State") {
                StateGroup.entries.forEach { group ->
                    Chip(group.label, group in filters.states) {
                        onChange(filters.copy(states = filters.states.toggle(group)))
                    }
                }
            }

            // Absent rather than empty before the first refresh fills the column in: an
            // empty row of chips under a heading reads as a broken screen.
            if (genres.isNotEmpty()) {
                Section("Genre") {
                    genres.forEach { genre ->
                        Chip(genre, genre in filters.genres) {
                            onChange(filters.copy(genres = filters.genres.toggle(genre)))
                        }
                    }
                }
            }

            Section("Episode length") {
                RuntimeBand.entries.forEach { band ->
                    Chip(band.label, band == filters.runtime) {
                        // Tapping the chosen one lets go, the same tap-again-to-undo the
                        // season rows use. Without it a single-select row is a trap: there
                        // is no other way back to "any length".
                        onChange(filters.copy(runtime = band.takeIf { it != filters.runtime }))
                    }
                }
            }

            Section("Score") {
                ScoreFloor.entries.forEach { floor ->
                    Chip(floor.label, floor == filters.score) {
                        onChange(filters.copy(score = floor.takeIf { it != filters.score }))
                    }
                }
            }

            Text(
                "Shows TMDB has no length or score for are hidden while those filters are " +
                    "on, since there is nothing to judge them against.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )
        }
    }
}

private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = TextMuted,
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(selected = selected, onClick = onClick, label = { Text(label) })
}
