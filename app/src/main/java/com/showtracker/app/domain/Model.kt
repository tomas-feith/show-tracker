package com.showtracker.app.domain

import java.time.LocalDate

/**
 * Dates arrive from TMDB, and leave in an export, as `YYYY-MM-DD` strings, so that is how
 * they are stored. They are parsed to [LocalDate] only where arithmetic happens, which
 * keeps the persisted and exported shapes identical to what the React Native build wrote
 * and lets an export move between the two without a translation layer.
 */
private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * Parse a stored date, treating anything unusable as absent.
 *
 * TMDB returns `""` rather than null for some missing dates, and occasionally a
 * syntactically valid but impossible one. Both mean "not scheduled", which is exactly how
 * a null is already treated everywhere downstream, so neither is worth an exception.
 */
fun parseIsoDate(value: String?): LocalDate? {
    if (value == null || !ISO_DATE.matches(value)) return null
    return runCatching { LocalDate.parse(value) }.getOrNull()
}

/** A single season as reported by TMDB. */
data class Season(
    val seasonNumber: Int,
    val name: String,
    /** ISO date, `YYYY-MM-DD`. Null when TMDB has announced no date. */
    val airDate: String? = null,
    val episodeCount: Int = 0,
) {
    val airDateOrNull: LocalDate? get() = parseIsoDate(airDate)
}

/** A specific episode, used for "last aired" and "next airing" markers. */
data class EpisodeRef(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String,
    val airDate: String? = null,
) {
    val airDateOrNull: LocalDate? get() = parseIsoDate(airDate)
}

/** Full detail for a show, as fetched from TMDB. */
data class ShowDetail(
    val id: Int,
    val name: String,
    val overview: String,
    val posterPath: String?,
    val firstAirDate: String?,
    /** TMDB production status, e.g. "Returning Series", "Ended", "Canceled". */
    val status: String,
    val seasons: List<Season>,
    val lastEpisode: EpisodeRef?,
    val nextEpisode: EpisodeRef?,
)

/**
 * A search hit, which carries less data than a full detail fetch.
 *
 * Also what the recommendation and trending lists return - TMDB gives all three the same
 * shape. The vote fields are only read when ranking suggestions, and default so that a
 * payload without them (or a caller building one in a test) is still valid.
 */
data class SearchResult(
    val id: Int,
    val name: String,
    val overview: String,
    val posterPath: String?,
    val firstAirDate: String?,
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
)

/**
 * A show the user follows. This is the persisted shape.
 *
 * Two separate watermarks, deliberately not merged, because they answer different
 * questions:
 *
 * - [watchedThroughSeason] is the user's own progress, and drives how far behind they are.
 * - [knownAiredSeason] is what the app has already told them about, and only stops the
 *   same season being announced twice.
 *
 * Collapsing them would mean dismissing a notification silently claimed you had watched
 * the season, or that marking a season watched suppressed the alert for the next one.
 */
data class TrackedShow(
    val id: Int,
    val name: String,
    /**
     * TMDB's synopsis. Stored rather than fetched on demand so the library reads the same
     * offline as on, and empty when TMDB has none - which it genuinely does for some shows.
     */
    val overview: String = "",
    val posterPath: String? = null,
    val firstAirDate: String? = null,
    val status: String = "",
    val seasons: List<Season> = emptyList(),
    val lastEpisode: EpisodeRef? = null,
    val nextEpisode: EpisodeRef? = null,
    /**
     * The highest season the user says they have finished. 0 means not started. Anything
     * aired above this is backlog.
     */
    val watchedThroughSeason: Int = 0,
    /**
     * The season the user is partway through, or null when nothing is in progress.
     *
     * A third piece of state rather than a half-step on [watchedThroughSeason], because
     * "started season 4" and "finished season 3" are not the same claim: the first says
     * where to resume, the second is what the backlog count is measured from. A fractional
     * or off-by-one watermark would have to mean both at once.
     *
     * Only ever one season: this records where the user is, not a set of things half-seen.
     * The marker is dropped once [watchedThroughSeason] reaches it, since a finished season
     * is no longer in progress.
     */
    val inProgressSeason: Int? = null,
    /**
     * The latest aired season number as observed at the last check.
     *
     * Recorded rather than recomputed: the stored season list is always re-evaluated
     * against today's date, so a season TMDB listed months early would appear to have
     * "always been aired" once its date arrives, and the moment it actually dropped would
     * pass unnoticed.
     */
    val knownAiredSeason: Int = 0,
    /** ISO timestamp. */
    val addedAt: String = "",
    /** ISO timestamp, null until the first refresh completes. */
    val lastCheckedAt: String? = null,
)

/** A season that appeared between two refreshes and is above the watermark. */
data class Discovery(
    val show: TrackedShow,
    val season: Season,
)

/**
 * How a tracked show should be presented, derived fresh from its data.
 *
 * A sealed hierarchy rather than the TypeScript discriminated union it replaces: the
 * compiler now enforces that every `when` handles every case, so adding a state cannot
 * silently fall through a branch somewhere.
 */
sealed interface ShowState {
    /**
     * A season the user has started and not finished. Outranks [Behind], because a season
     * already underway is a better answer to "what do I put on" than one never started.
     *
     * [seasonsWaiting] is the rest of the backlog: every aired, unwatched season other than
     * this one, so the row can still say how much is left once it is finished. Counted
     * against the watermark rather than against this season's number, because a backlog can
     * sit below the season in progress as easily as above it - someone who skips ahead to
     * the newest season still has the older ones waiting.
     */
    data class Watching(
        val season: Season,
        val seasonsWaiting: Int,
    ) : ShowState

    /** Aired seasons the user has not watched. [seasonsBehind] is at least 1. */
    data class Behind(
        val latest: Season,
        val seasonsBehind: Int,
        val daysAgo: Int,
    ) : ShowState

    data class Airing(
        val next: EpisodeRef,
        val daysUntil: Int,
    ) : ShowState

    /**
     * A season still releasing episodes when there is no dated next episode to name.
     *
     * TMDB clears `next_episode_to_air` in the gap between two episodes, not only after a
     * finale, so a weekly show spends part of every week with no next episode published.
     * Without this the show would fall back to reading as a backlog for those days and
     * return to airing when the marker reappeared, which is the state flapping rather than
     * anything about the show changing.
     *
     * [episodesAired] is 0 when even that is unknown.
     */
    data class Running(
        val season: Season,
        val episodesAired: Int,
        val episodeCount: Int,
    ) : ShowState

    data class Upcoming(
        val season: Season,
        val daysUntil: Int,
    ) : ShowState

    /** A returning series with nothing scheduled yet. */
    data object Waiting : ShowState

    data object Ended : ShowState
}
