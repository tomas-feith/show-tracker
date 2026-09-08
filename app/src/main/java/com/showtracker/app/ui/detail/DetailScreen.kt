package com.showtracker.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.showtracker.app.domain.SearchResult
import com.showtracker.app.domain.Season
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.domain.hasAired
import com.showtracker.app.domain.realSeasons
import com.showtracker.app.domain.seasonInProgress
import com.showtracker.app.ui.LibraryViewModel
import com.showtracker.app.ui.components.Divider
import com.showtracker.app.ui.components.Poster
import com.showtracker.app.ui.components.Preview
import com.showtracker.app.ui.components.PreviewSheet
import com.showtracker.app.ui.components.ResultRow
import com.showtracker.app.ui.components.ShowMeta
import com.showtracker.app.ui.components.describeKind
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
    /** Owns the "more like this" list and its preview sheet; see [ShowDetailViewModel]. */
    detailViewModel: ShowDetailViewModel,
    onBack: () -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val show = state.shows.firstOrNull { it.id == showId }
    var confirmingRemove by remember { mutableStateOf(false) }

    val similar by detailViewModel.similar.collectAsStateWithLifecycle()
    val preview by detailViewModel.preview.collectAsStateWithLifecycle()
    val tracked = state.shows.map { it.id }.toSet()

    // Keyed on the key as well as the show: on a cold start the library state arrives
    // before the settings do, and a load fired against a null key would fail the section
    // for the whole visit.
    LaunchedEffect(showId, state.apiKey) {
        if (state.apiKey != null) detailViewModel.load(showId)
    }

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
                        // Given the value it is moving to rather than toggling what is
                        // stored, so a double tap lands where the second tap pointed.
                        FavouriteToggle(
                            favourite = show.favourite,
                            name = show.name,
                            onToggle = { viewModel.setFavourite(show.id, it) },
                        )
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
                        describeKind(show),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    ShowMeta(show, withVotes = true)
                }
            }

            GenreRow(show.genres)

            if (show.overview.isNotBlank()) {
                Text(
                    show.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                )
            }

            Text(
                "Tap the last season you finished. Everything above it is backlog; tap it " +
                    "again to clear. Use the play button to mark the one you are partway " +
                    "through.",
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
            )

            SeasonList(show, today, viewModel)

            SimilarSection(
                similar = similar,
                tracked = tracked,
                onOpen = detailViewModel::openPreview,
                onDismissError = detailViewModel::dismissError,
            )
        }
    }

    preview?.let { showing ->
        SuggestionSheet(showing, tracked, viewModel, detailViewModel)
    }

    if (confirmingRemove && show != null) {
        ConfirmRemoval(
            name = show.name,
            onConfirm = {
                confirmingRemove = false
                viewModel.removeShow(show.id)
                onBack()
            },
            onCancel = { confirmingRemove = false },
        )
    }
}

/**
 * The one destructive action on this screen, behind a confirmation.
 *
 * Worth confirming because it is the only thing here that loses data the app cannot get
 * back: the seasons come from TMDB, the watched-through position does not.
 */
@Composable
private fun ConfirmRemoval(
    name: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Stop following $name?") },
        text = { Text("Your watched-through position for this show is forgotten.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Stop following", color = Danger) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
    )
}

@Composable
private fun FavouriteToggle(
    favourite: Boolean,
    name: String,
    onToggle: (Boolean) -> Unit,
) {
    IconButton(onClick = { onToggle(!favourite) }) {
        Icon(
            if (favourite) Icons.Default.Star else Icons.Default.StarBorder,
            contentDescription =
                if (favourite) {
                    "Remove $name from favourites"
                } else {
                    "Add $name to favourites"
                },
            tint = if (favourite) Accent else TextMuted,
        )
    }
}

/**
 * The same preview sheet the discovery screen uses, on the same terms.
 *
 * Following from here is the point of the section, and "Not interested" is offered because
 * these rows are suggestions: a show turned down here is turned down everywhere, which is
 * what the button already promised on the other screen.
 */
@Composable
private fun SuggestionSheet(
    showing: Preview,
    tracked: Set<Int>,
    viewModel: LibraryViewModel,
    detailViewModel: ShowDetailViewModel,
) {
    PreviewSheet(
        preview = showing,
        alreadyFollowing = showing.id in tracked,
        onFollow = { seenUpTo ->
            viewModel.addShow(showing.id, seenUpTo, detailViewModel::showError)
            detailViewModel.onFollowed(showing.id)
            detailViewModel.closePreview()
        },
        onDismissShow = { detailViewModel.dismiss(showing.id) },
        onClose = detailViewModel::closePreview,
    )
}

/**
 * "More like this", at the foot of the show.
 *
 * TMDB's list for this one show, in TMDB's order. The discovery screen ranks its pool by
 * agreement between several of the user's shows, and with a single seed there is no
 * agreement to measure - so re-ordering here would only replace TMDB's own confidence with
 * a tiebreak that means nothing.
 *
 * A followed show stays in the list wearing the "already following" tick rather than being
 * dropped. The discovery tab drops them because it answers "what next", and something you
 * already have is not an answer to that; this section answers "what is this show like", and
 * being shown three you already follow is part of the answer.
 *
 * Nothing is drawn until there is something to draw: a failed fetch is one dismissable
 * line, and an empty one is silence rather than a heading over a gap.
 */
@Composable
private fun SimilarSection(
    similar: SimilarShows,
    tracked: Set<Int>,
    onOpen: (SearchResult) -> Unit,
    onDismissError: () -> Unit,
) {
    if (similar.loading) {
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        return
    }

    similar.error?.let { message ->
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = Danger,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onDismissError),
        )
        return
    }

    if (similar.items.isEmpty()) return

    Text(
        "More like this",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onBackground,
    )

    // A plain column, not a LazyColumn: the whole screen is already one scrolling column,
    // and a lazy list nested inside it has no height of its own to be lazy about.
    Column(Modifier.fillMaxWidth()) {
        similar.items.forEach { result ->
            ResultRow(
                name = result.name,
                posterPath = result.posterPath,
                subtitle = result.firstAirDate?.take(4) ?: "Date unknown",
                tracked = result.id in tracked,
                onClick = { onOpen(result) },
                voteAverage = result.voteAverage,
                voteCount = result.voteCount,
                // The screen's column already carries the 16dp side inset these rows
                // normally add for themselves.
                horizontalPadding = 0.dp,
            )
            Divider()
        }
    }
}

/**
 * Genre tags.
 *
 * Scrolled sideways rather than wrapped: a wrap would change the height of the header for
 * some shows and not others, so the synopsis below it would sit at a different place on
 * every screen. Three or four tags is the normal case and fits without scrolling at all.
 */
@Composable
private fun GenreRow(genres: List<String>) {
    if (genres.isEmpty()) return

    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        genres.forEach { genre ->
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceAlt)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    genre,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 12.sp,
                    color = TextMuted,
                )
            }
        }
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

        // The year sits in its own column ahead of the name, so the release dates line up
        // down the list and can be scanned in one pass. That is the question this answers -
        // "was this the one I watched two summers ago?" - and it is not answerable when the
        // dates are buried at varying offsets inside a sentence.
        Text(
            season.airDate?.take(4) ?: "----",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = if (season.airDate == null) TextFaint else TextMuted,
        )

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
