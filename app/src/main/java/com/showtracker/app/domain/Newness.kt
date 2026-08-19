package com.showtracker.app.domain

import java.text.Collator
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Whole days from [from] to [to]. Positive means [to] is later.
 *
 * [LocalDate] carries no time zone or offset, so the result is a pure calendar difference
 * and cannot be skewed by the device's zone or by a daylight saving transition. Using
 * `Instant` here instead would reintroduce exactly that skew.
 */
fun daysBetween(
    from: LocalDate,
    to: LocalDate,
): Int = ChronoUnit.DAYS.between(from, to).toInt()

/** True when [date] is today or earlier. A null date has not happened. */
fun hasHappened(
    date: LocalDate?,
    today: LocalDate,
): Boolean = date != null && !date.isAfter(today)

/**
 * Real seasons only: TMDB files specials, recaps and shorts under season 0, which must
 * never count as a new season.
 */
fun realSeasons(seasons: List<Season>): List<Season> = seasons.filter { it.seasonNumber >= 1 }

/**
 * Whether a single season has actually started airing: a real season number, a past air
 * date, and at least one episode. The single definition of "aired", used everywhere so the
 * badge, the backlog count and the notification can never disagree.
 */
fun hasAired(
    season: Season,
    today: LocalDate,
): Boolean =
    season.seasonNumber >= 1 &&
        season.episodeCount > 0 &&
        hasHappened(season.airDateOrNull, today)

/**
 * The highest-numbered season that has actually started airing.
 *
 * TMDB routinely lists a future season months before release, sometimes with a null air
 * date and sometimes with a dated placeholder, so an unfiltered "max season number" would
 * announce a season nobody can watch yet. At least one episode is required too, because
 * empty placeholder seasons occasionally carry a past air date.
 */
fun latestAiredSeason(
    seasons: List<Season>,
    today: LocalDate,
): Season? = seasons.filter { hasAired(it, today) }.maxByOrNull { it.seasonNumber }

/** The nearest season that is announced but has not started airing yet. */
fun nextUnairedSeason(
    seasons: List<Season>,
    today: LocalDate,
): Season? =
    realSeasons(seasons)
        .filter { it.airDateOrNull != null && !hasHappened(it.airDateOrNull, today) }
        .minByOrNull { it.seasonNumber }

/**
 * The season number to record as "already known" when a show is first added, so that
 * following a long-running show does not immediately report news.
 */
fun initialWatermark(
    seasons: List<Season>,
    today: LocalDate,
): Int = latestAiredSeason(seasons, today)?.seasonNumber ?: 0

/**
 * How many aired seasons the user has not watched.
 *
 * Counts only seasons that actually exist and have aired, so a watermark left behind by a
 * show that later removed a season cannot report a negative or inflated backlog.
 */
fun seasonsBehind(
    show: TrackedShow,
    today: LocalDate,
): Int =
    show.seasons.count {
        hasAired(it, today) && it.seasonNumber > show.watchedThroughSeason
    }

/**
 * The season the user is partway through, or null.
 *
 * The stored marker is a season *number*, and is validated against today's season list on
 * every read rather than trusted: TMDB can withdraw a season, and marking one watched
 * leaves the marker behind. Resolving it here means one definition of "in progress" that
 * the row, the detail screen and the sort all share, and a stale marker simply stops
 * counting instead of having to be cleaned up everywhere it might be read.
 */
fun seasonInProgress(
    show: TrackedShow,
    today: LocalDate,
): Season? {
    val number = show.inProgressSeason ?: return null
    // Finished seasons are not in progress, whatever the marker still says.
    if (number <= show.watchedThroughSeason) return null
    return show.seasons.firstOrNull { it.seasonNumber == number && hasAired(it, today) }
}

/** True when there is at least one aired season the user has not watched. */
fun isBehind(
    show: TrackedShow,
    today: LocalDate,
): Boolean = seasonsBehind(show, today) > 0

/**
 * Derive how a show should be presented. Precedence is deliberate: a season the user has
 * started outranks everything, then an unwatched aired season, since resuming and catching
 * up are the point of the app.
 */
fun showState(
    show: TrackedShow,
    today: LocalDate,
): ShowState {
    val latest = latestAiredSeason(show.seasons, today)
    val behind = seasonsBehind(show, today)
    val inProgress = seasonInProgress(show, today)
    val next = show.nextEpisode
    val nextAirs = next?.airDateOrNull
    val upcoming = nextUnairedSeason(show.seasons, today)
    val upcomingAirs = upcoming?.airDateOrNull

    // Branch order is the precedence: a season already underway outranks everything, and
    // an unwatched aired season outranks the rest.
    return when {
        inProgress != null -> {
            // The season in progress is itself unwatched and aired, so it is one of the
            // seasons `behind` counts; the rest is what is still waiting.
            ShowState.Watching(inProgress, behind - 1)
        }

        latest != null && behind > 0 -> {
            ShowState.Behind(
                latest = latest,
                seasonsBehind = behind,
                daysAgo = latest.airDateOrNull?.let { daysBetween(it, today) } ?: 0,
            )
        }

        next != null && nextAirs != null && !hasHappened(nextAirs, today) -> {
            ShowState.Airing(next, daysBetween(today, nextAirs))
        }

        upcoming != null && upcomingAirs != null -> {
            ShowState.Upcoming(upcoming, daysBetween(today, upcomingAirs))
        }

        show.status in TERMINAL_STATUSES -> {
            ShowState.Ended
        }

        else -> {
            ShowState.Waiting
        }
    }
}

/**
 * TMDB statuses that mean no further seasons are coming. Everything else - including
 * "In Production", which appears in a real library - is a show that may yet return.
 */
private val TERMINAL_STATUSES = setOf("Ended", "Canceled")

/** Sort weight per state: lower sorts first. */
private val ShowState.order: Int
    get() =
        when (this) {
            is ShowState.Watching -> 0
            is ShowState.Behind -> 1
            is ShowState.Airing -> 2
            is ShowState.Upcoming -> 3
            ShowState.Waiting -> 4
            ShowState.Ended -> 5
        }

/**
 * Order the library so the things demanding attention float to the top: seasons already
 * underway first, then unseen new seasons (most recent drop first), then imminent episodes,
 * then announced seasons by nearness, then everything dormant by name.
 */
fun sortLibrary(
    shows: List<TrackedShow>,
    today: LocalDate,
): List<TrackedShow> {
    // Derived once per show rather than on every comparison: showState walks the season
    // list, and a comparator is called O(n log n) times.
    val states = shows.associate { it.id to showState(it, today) }

    return shows.sortedWith(
        compareBy<TrackedShow> { states.getValue(it.id).order }
            .thenBy { show ->
                when (val state = states.getValue(show.id)) {
                    // Nothing to rank shows in progress by but their names, below.
                    is ShowState.Watching -> 0

                    // Most recent drop first.
                    is ShowState.Behind -> state.daysAgo

                    is ShowState.Airing -> state.daysUntil

                    is ShowState.Upcoming -> state.daysUntil

                    else -> 0
                }
            }.thenByDescending { show ->
                // A deeper backlog breaks a same-day tie.
                (states.getValue(show.id) as? ShowState.Behind)?.seasonsBehind ?: 0
            }.thenBy(nameOrder) { it.name },
    )
}

/**
 * Accent-aware, case-insensitive name ordering, standing in for the JavaScript
 * `localeCompare` this replaces. Kotlin's natural `String` ordering compares UTF-16 code
 * units, which would file "Glória" after "Grimm" rather than before it.
 */
private val nameOrder: Comparator<String> =
    Collator
        .getInstance()
        .apply { strength = Collator.SECONDARY }
        // Collator implements the raw Comparator<Object>, so it needs wrapping to be
        // usable as a Comparator<String>.
        .let { collator -> Comparator { a, b -> collator.compare(a, b) } }

private const val DAYS_PER_MONTH = 30
private const val DAYS_PER_YEAR = 365
private const val DAYS_BEFORE_MONTHS = 30

/** Human-readable relative day count, e.g. "today", "in 3 days", "5 days ago". */
fun describeDays(
    days: Int,
    direction: Direction,
): String {
    if (days == 0) return "today"
    val n = abs(days)

    return when (direction) {
        Direction.AGO -> {
            when {
                n == 1 -> "yesterday"
                n < DAYS_BEFORE_MONTHS -> "$n days ago"
                n < DAYS_PER_YEAR -> "${monthsIn(n)} months ago"
                n < 2 * DAYS_PER_YEAR -> "over a year ago"
                else -> "${n / DAYS_PER_YEAR} years ago"
            }
        }

        Direction.UNTIL -> {
            when {
                n == 1 -> "tomorrow"
                n < DAYS_BEFORE_MONTHS -> "in $n days"
                n < DAYS_PER_YEAR -> "in ${monthsIn(n)} months"
                else -> "over a year away"
            }
        }
    }
}

/**
 * Rounded, not truncated. Kotlin's integer division would turn 180 days into 6 months only
 * by luck and 175 into 5, where the JavaScript this replaces used `Math.round` throughout.
 */
private fun monthsIn(days: Int): Int = (days.toDouble() / DAYS_PER_MONTH).roundToInt()

enum class Direction { AGO, UNTIL }

/** Format an episode marker as `S02E05`. */
fun formatEpisode(episode: EpisodeRef): String {
    val s = episode.seasonNumber.toString().padStart(2, '0')
    val e = episode.episodeNumber.toString().padStart(2, '0')
    return "S${s}E$e"
}
