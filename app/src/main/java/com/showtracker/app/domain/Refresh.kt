package com.showtracker.app.domain

import java.time.Instant
import java.time.LocalDate

/**
 * Fold fresh TMDB data into a tracked show, preserving the user's own state.
 *
 * [TrackedShow.watchedThroughSeason] and [TrackedShow.addedAt] belong to the user, not to
 * TMDB, and must survive every refresh: overwriting the watermark here would silently mark
 * unwatched seasons as watched.
 */
fun mergeShow(
    existing: TrackedShow,
    detail: ShowDetail,
    now: Instant = Instant.now(),
    today: LocalDate = LocalDate.now(),
): TrackedShow =
    existing.copy(
        name = detail.name,
        posterPath = detail.posterPath,
        firstAirDate = detail.firstAirDate,
        status = detail.status,
        seasons = detail.seasons,
        lastEpisode = detail.lastEpisode,
        nextEpisode = detail.nextEpisode,
        knownAiredSeason =
            latestAiredSeason(detail.seasons, today)?.seasonNumber ?: existing.knownAiredSeason,
        lastCheckedAt = now.toString(),
    )

/**
 * Whether this refresh uncovered a season worth announcing.
 *
 * Announce only on the *transition* - when the latest aired season number actually rises -
 * rather than whenever an unwatched season exists. Otherwise every refresh would re-notify
 * about a season sitting in the backlog.
 *
 * The comparison uses the recorded [TrackedShow.knownAiredSeason] rather than recomputing
 * from the previous season list. Recomputing would evaluate an old list against today's
 * date, so a season TMDB had already listed with a future date would look like it had
 * aired all along, and its actual release would never be announced - precisely the case
 * this app exists to catch.
 */
fun findDiscovery(
    before: TrackedShow,
    after: TrackedShow,
    today: LocalDate,
): Season? {
    val next = latestAiredSeason(after.seasons, today) ?: return null

    if (next.seasonNumber <= before.knownAiredSeason) return null
    // Already watched: the user got there ahead of us and needs no telling.
    if (next.seasonNumber <= after.watchedThroughSeason) return null

    return next
}
