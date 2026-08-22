package com.showtracker.app.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.showtracker.app.domain.ShowDetail
import com.showtracker.app.domain.formatEpisode
import com.showtracker.app.domain.realSeasons
import com.showtracker.app.ui.components.Poster
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.Danger
import com.showtracker.app.ui.theme.StateAiring
import com.showtracker.app.ui.theme.Surface
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted

/**
 * What a suggestion actually is, before following it.
 *
 * Tapping a row used to follow the show outright, which made the list a minefield: the
 * whole point of a suggestion is that the user does not yet know what it is, so the one
 * gesture available should be "tell me more", not "commit". Following now takes a
 * deliberate second tap, and the sheet is also where a show can be turned down for good.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(
    preview: Preview,
    alreadyFollowing: Boolean,
    onFollow: () -> Unit,
    onDismissShow: () -> Unit,
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
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Poster(preview.posterPath, width = 96.dp, height = 144.dp)

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        preview.name,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        subtitle(preview),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                    preview.detail?.let { Counts(it) }
                }
            }

            when {
                preview.loading -> {
                    Box(
                        Modifier.fillMaxWidth().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }

                preview.error != null -> {
                    Text(
                        preview.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Danger,
                    )
                }

                else -> {
                    Synopsis(preview.detail)
                }
            }

            Actions(alreadyFollowing, preview.loading, onFollow, onDismissShow)
        }
    }
}

/** Seasons and episodes: the two numbers that decide whether a show is worth starting. */
@Composable
private fun Counts(detail: ShowDetail) {
    val seasons = realSeasons(detail.seasons)
    // Specials are excluded, as everywhere else in the app: a season 0 of eleven featurettes
    // is not something anyone counts as a season of the show.
    val episodes = seasons.sumOf { it.episodeCount }

    Text(
        buildString {
            append(if (seasons.size == 1) "1 season" else "${seasons.size} seasons")
            if (episodes > 0) {
                append(" - ")
                append(if (episodes == 1) "1 episode" else "$episodes episodes")
            }
        },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = Accent,
    )
}

@Composable
private fun Synopsis(detail: ShowDetail?) {
    if (detail == null) return

    if (detail.overview.isBlank()) {
        Text(
            "TMDB has no synopsis for this one.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextFaint,
        )
    } else {
        Text(
            detail.overview,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }

    detail.nextEpisode?.let {
        Text(
            "Next: ${formatEpisode(it)}${it.airDate?.let { date -> " on $date" }.orEmpty()}",
            style = MaterialTheme.typography.bodySmall,
            color = StateAiring,
        )
    }
}

@Composable
private fun Actions(
    alreadyFollowing: Boolean,
    loading: Boolean,
    onFollow: () -> Unit,
    onDismissShow: () -> Unit,
) {
    if (alreadyFollowing) {
        Text(
            "Already in your shows.",
            style = MaterialTheme.typography.bodyMedium,
            color = StateAiring,
        )
        return
    }

    Button(
        onClick = onFollow,
        // Enabled before the detail arrives: the id is all following needs, and blocking
        // on a synopsis the user may not care about would make the sheet feel slower than
        // the old tap-to-add it replaced.
        enabled = true,
        colors = ButtonDefaults.buttonColors(containerColor = Accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Follow this show")
    }

    OutlinedButton(
        onClick = onDismissShow,
        enabled = !loading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Not interested", color = TextMuted)
    }

    Text(
        "\"Not interested\" hides this show from suggestions for good. It has no effect " +
            "on search - you can still follow it later.",
        style = MaterialTheme.typography.bodySmall,
        color = TextFaint,
    )
}

private fun subtitle(preview: Preview): String {
    val year = preview.firstAirDate?.take(4) ?: preview.detail?.firstAirDate?.take(4)
    val status = preview.detail?.status?.takeIf { it.isNotBlank() }
    return listOfNotNull(year, status).joinToString(" - ").ifEmpty { "Date unknown" }
}
