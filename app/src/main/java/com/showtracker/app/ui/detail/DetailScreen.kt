package com.showtracker.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.showtracker.app.domain.Season
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.domain.hasAired
import com.showtracker.app.domain.realSeasons
import com.showtracker.app.domain.seasonInProgress
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.components.Poster
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.Border
import com.showtracker.app.ui.theme.Danger
import com.showtracker.app.ui.theme.StateAiring
import com.showtracker.app.ui.theme.StateNew
import com.showtracker.app.ui.theme.Surface
import com.showtracker.app.ui.theme.SurfaceAlt
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    showId: Int,
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val show = state.shows.firstOrNull { it.id == showId }
    var confirmingRemove by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(show?.name.orEmpty(), maxLines = 1) },
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
                    if (show != null) {
                        IconButton(onClick = { confirmingRemove = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Stop following",
                                tint = TextMuted,
                            )
                        }
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
        if (show == null) {
            // Reachable for a moment after removing the show, before navigation unwinds.
            Box(Modifier.padding(insets).fillMaxSize())
            return@Scaffold
        }

        Column(
            Modifier
                .padding(insets)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Poster(show.posterPath, width = 100.dp, height = 150.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        show.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        listOfNotNull(show.firstAirDate?.take(4), show.status)
                            .joinToString(" - "),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }

            Text(
                "Tap the last season you finished. Everything above it is backlog; tap it " +
                    "again to clear. Use the play button to mark the one you are partway " +
                    "through.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )

            SeasonList(show, today, viewModel)
        }
    }

    if (confirmingRemove && show != null) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Stop following ${show.name}?") },
            text = { Text("Your watched-through position for this show is forgotten.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    viewModel.removeShow(show.id)
                    onBack()
                }) {
                    Text("Stop following", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The season list, and the two things a tap can mean.
 *
 * Extracted from [DetailScreen] so each stays readable, not because it is reused.
 */
@Composable
private fun SeasonList(
    show: TrackedShow,
    today: LocalDate,
    viewModel: LibraryViewModel,
) {
    val inProgress = seasonInProgress(show, today)

    realSeasons(show.seasons)
        .sortedBy { it.seasonNumber }
        .forEach { season ->
            SeasonRow(
                season = season,
                show = show,
                today = today,
                inProgress = inProgress?.seasonNumber == season.seasonNumber,
                onToggleInProgress = {
                    // Tapping the season already in progress clears it, the same
                    // tap-again-to-undo convention the watermark uses.
                    val next =
                        season.seasonNumber.takeIf {
                            inProgress?.seasonNumber != it
                        }
                    viewModel.setInProgress(show.id, next)
                },
                onTap = {
                    // Tapping the current watermark clears it, so a mis-tap is undoable
                    // without a separate control.
                    val next =
                        if (show.watchedThroughSeason == season.seasonNumber) {
                            season.seasonNumber - 1
                        } else {
                            season.seasonNumber
                        }
                    viewModel.setWatchedThrough(show.id, next)
                },
            )
        }
}

/**
 * How one season row reads, given where the user has got to.
 *
 * Plain data computed outside the composable: the three branches all key off the same two
 * facts, and reading them together is what makes it obvious that "watching" wins over
 * "backlog" consistently in the badge, the caption and its colour.
 */
private data class SeasonLook(
    val badge: Color,
    val caption: String,
    val captionColor: Color,
)

private fun seasonLook(
    season: Season,
    aired: Boolean,
    watched: Boolean,
    inProgress: Boolean,
): SeasonLook {
    val backlog = aired && !watched

    return SeasonLook(
        badge =
            when {
                inProgress -> StateAiring
                backlog -> StateNew
                watched -> Accent
                else -> Border
            },
        caption =
            when {
                !aired && season.airDate != null -> "Airs ${season.airDate}"
                !aired -> "No date yet"
                inProgress -> "Watching - ${season.episodeCount} episodes"
                watched -> "Watched"
                else -> "${season.episodeCount} episodes"
            },
        captionColor =
            when {
                inProgress -> StateAiring
                backlog -> StateNew
                else -> TextMuted
            },
    )
}

@Composable
private fun SeasonRow(
    season: Season,
    show: TrackedShow,
    today: LocalDate,
    inProgress: Boolean,
    onToggleInProgress: () -> Unit,
    onTap: () -> Unit,
) {
    val aired = hasAired(season, today)
    val watched = season.seasonNumber <= show.watchedThroughSeason
    val look = seasonLook(season, aired, watched, inProgress)

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (watched) SurfaceAlt else Surface)
            .clickable(enabled = aired, onClick = onTap)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(look.badge)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(
                "S${season.seasonNumber}",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = if (aired) MaterialTheme.colorScheme.onBackground else TextFaint,
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                season.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                look.caption,
                style = MaterialTheme.typography.bodySmall,
                color = look.captionColor,
            )
        }

        // Offered only for a season that can actually be underway. A finished one is not in
        // progress, and an unaired one cannot be started, so the control would invite a tap
        // the repository refuses.
        if (aired && !watched) {
            InProgressToggle(season.seasonNumber, inProgress, onToggleInProgress)
        }
    }
}

@Composable
private fun InProgressToggle(
    seasonNumber: Int,
    inProgress: Boolean,
    onToggle: () -> Unit,
) {
    IconButton(onClick = onToggle) {
        Icon(
            Icons.Default.PlayArrow,
            contentDescription =
                if (inProgress) {
                    "Stop marking season $seasonNumber as in progress"
                } else {
                    "Mark season $seasonNumber as in progress"
                },
            tint = if (inProgress) StateAiring else TextFaint,
        )
    }
}
