package com.showtracker.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.showtracker.app.domain.Direction
import com.showtracker.app.domain.ShowState
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.domain.describeDays
import com.showtracker.app.domain.formatEpisode
import com.showtracker.app.domain.showState
import com.showtracker.app.network.posterUrl
import com.showtracker.app.ui.theme.Accent
import com.showtracker.app.ui.theme.Border
import com.showtracker.app.ui.theme.StateAiring
import com.showtracker.app.ui.theme.StateNew
import com.showtracker.app.ui.theme.SurfaceAlt
import com.showtracker.app.ui.theme.TextFaint
import com.showtracker.app.ui.theme.TextMuted
import java.time.LocalDate

private val POSTER_WIDTH = 54.dp
private val POSTER_HEIGHT = 81.dp

/**
 * A poster, or a flat placeholder when the show has none.
 *
 * TMDB genuinely returns a null poster path, so the placeholder is a real case rather than
 * a loading state.
 */
@Composable
fun Poster(
    path: String?,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = POSTER_WIDTH,
    height: androidx.compose.ui.unit.Dp = POSTER_HEIGHT,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(SurfaceAlt),
    ) {
        val url = posterUrl(path)
        if (url != null) {
            AsyncImage(
                model = url,
                // Decorative: the show's name is already the row's primary label, so a
                // screen reader announcing it twice is noise.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** The colour and words for a show's current state. */
private data class StateLabel(
    val text: String,
    val color: Color,
)

private fun label(
    state: ShowState,
    show: TrackedShow,
): StateLabel =
    when (state) {
        is ShowState.Watching -> {
            StateLabel(
                text =
                    if (state.seasonsWaiting == 0) {
                        "Watching season ${state.season.seasonNumber}"
                    } else {
                        "Watching season ${state.season.seasonNumber} - " +
                            "${state.seasonsWaiting} more waiting"
                    },
                color = StateAiring,
            )
        }

        is ShowState.Behind -> {
            StateLabel(
                text =
                    if (state.seasonsBehind == 1) {
                        "Season ${state.latest.seasonNumber} out " +
                            describeDays(state.daysAgo, Direction.AGO)
                    } else {
                        "${state.seasonsBehind} seasons behind"
                    },
                color = StateNew,
            )
        }

        is ShowState.Airing -> {
            StateLabel(
                "${formatEpisode(state.next)} ${describeDays(state.daysUntil, Direction.UNTIL)}",
                StateAiring,
            )
        }

        is ShowState.Upcoming -> {
            StateLabel(
                "Season ${state.season.seasonNumber} ${describeDays(
                    state.daysUntil,
                    Direction.UNTIL,
                )}",
                Accent,
            )
        }

        ShowState.Waiting -> {
            StateLabel("No date yet", TextFaint)
        }

        ShowState.Ended -> {
            StateLabel(if (show.status == "Canceled") "Canceled" else "Ended", TextFaint)
        }
    }

@Composable
fun ShowRow(
    show: TrackedShow,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val state = showState(show, today)
    val stateLabel = label(state, show)

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Poster(show.posterPath)

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = show.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stateLabel.text,
                style = MaterialTheme.typography.bodySmall,
                color = stateLabel.color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (show.watchedThroughSeason > 0) {
                Text(
                    text = "Watched through season ${show.watchedThroughSeason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // A dot rather than a badge: it marks the rows worth looking at without competing
        // with the title for attention.
        if (state is ShowState.Behind) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(StateNew)
                    // Announced as part of the row's own description instead.
                    .clearAndSetSemantics { },
            )
        }
    }
}

/** A one-pixel rule, used between rows and around cards. */
@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Border),
    )
}

/** Row semantics as a screen reader should hear them: one sentence, not four labels. */
fun describeRow(
    show: TrackedShow,
    today: LocalDate,
): String = "${show.name}. ${label(showState(show, today), show).text}"

@Composable
fun RowSemantics(
    show: TrackedShow,
    today: LocalDate,
    content: @Composable () -> Unit,
) {
    Box(Modifier.clearAndSetSemantics { contentDescription = describeRow(show, today) }) {
        content()
    }
}
