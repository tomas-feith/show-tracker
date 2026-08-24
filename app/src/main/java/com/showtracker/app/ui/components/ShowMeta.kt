package com.showtracker.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.ui.theme.StateNew
import com.showtracker.app.ui.theme.TextFaint
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

// TMDB's score and the shape of a show, shared by the library rows and the detail screen.
// Shared because the two answer the same question in different places - "is this worth
// putting on tonight" - and a score that rounded one way while scrolling and another way on
// the show's own page would read as two different numbers for the same show.

/**
 * One decimal place, rounded the way a reader would round it.
 *
 * [BigDecimal.valueOf] rather than `String.format("%.1f")` or the `BigDecimal(Double)`
 * constructor, both of which round the binary value actually held: 8.45 is stored as
 * 8.4499999999999993, so they render "8.4" where the number everyone else can see ends in
 * a 5. `valueOf` goes through the shortest decimal that round-trips - "8.45" - so the
 * rounding happens on the figure TMDB published rather than on its binary approximation.
 *
 * `toPlainString` also settles the separator without a locale: the phone's locale would
 * otherwise put a comma under text that is hardcoded English everywhere else.
 */
internal fun formatScore(voteAverage: Double): String =
    BigDecimal
        .valueOf(voteAverage)
        .setScale(1, RoundingMode.HALF_UP)
        .toPlainString()

/**
 * Episode count and length, as one phrase, or null when neither is known.
 *
 * Built with [listOfNotNull] rather than a `when` over the two: each half is independently
 * absent - TMDB leaves the runtime empty for a great many recent shows and the count at 0
 * for one it has only just listed - and every combination has to read correctly.
 *
 * The count leads because it is the figure that is nearly always there, and the length
 * trails it as "each", which is what makes the pair a phrase rather than two numbers:
 * "66 episodes - 60m each". Naming the length first gave "60m episodes - 66 episodes",
 * which reads as a mistake even though both halves are right. "each" is dropped where there
 * is nothing for it to distribute over - one episode, or no count at all.
 */
internal fun describeShape(show: TrackedShow): String? {
    val count = show.numberOfEpisodes.takeIf { it > 0 }
    val plural = count != null && count > 1

    val parts =
        listOfNotNull(
            count?.let { if (it == 1) "1 episode" else "$it episodes" },
            show.episodeRunTime?.let { if (plural) "${it}m each" else "${it}m" },
        )
    return parts.joinToString(" - ").takeIf { it.isNotEmpty() }
}

/** How many people voted, with thousands separated. */
private fun describeVotes(voteCount: Int): String =
    NumberFormat.getIntegerInstance(Locale.US).format(voteCount) + " votes"

/**
 * The score and shape line, or nothing at all when TMDB has given neither.
 *
 * A 0 vote count draws no score rather than "0.0". That is what a show holds both before
 * its first refresh and when nobody has rated it, and "0.0" reads as a damning review of
 * something that has simply not been judged.
 *
 * [withVotes] is for the detail screen, where the score is being read rather than scanned:
 * a reader judging "8.9" wants to know whether it came from 40,000 people or from 11. The
 * library rows leave it out, because while scrolling it is one number too many - and it is
 * also not what the recommender's damped ranking uses the count for.
 */
@Composable
fun ShowMeta(
    show: TrackedShow,
    modifier: Modifier = Modifier,
    withVotes: Boolean = false,
) {
    val scored = show.voteCount > 0
    val shape = describeShape(show)
    if (!scored && shape == null) return

    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (scored) {
            Icon(
                Icons.Default.Star,
                // Decorative: the score beside it is the label, and "star, 8.4" is not how
                // anyone reads a rating out.
                contentDescription = null,
                tint = StateNew,
                modifier = Modifier.size(13.dp),
            )
            Text(
                formatScore(show.voteAverage),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (withVotes) {
                Text(
                    describeVotes(show.voteCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextFaint,
                )
            }
        }

        if (shape != null) {
            Text(
                // The dash joins the two halves into one line; without a score in front of
                // it there is nothing to join, and a leading dash would look like a typo.
                if (scored) "- $shape" else shape,
                style = MaterialTheme.typography.bodySmall,
                color = TextFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
