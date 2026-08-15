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

/**
 * Fetches detail for many shows at once.
 *
 * An interface rather than the client itself, so the refresh below stays a pure function
 * over data and can be tested - including its failure handling - without a network or a
 * fake HTTP server.
 */
fun interface ShowFetcher {
    suspend fun fetch(ids: List<Int>): Map<Int, Result<ShowDetail>>
}

data class RefreshOutcome(
    val shows: List<TrackedShow>,
    /** Shows whose latest aired season rose during this refresh. */
    val discoveries: List<Discovery>,
    /** Shows that could not be refreshed, keyed by id, with the failure reason. */
    val failures: Map<Int, Throwable>,
)

/**
 * Refresh every tracked show against TMDB.
 *
 * Shows that fail to fetch keep their previous data rather than being dropped, so a flaky
 * connection degrades the library's freshness but never its contents.
 */
suspend fun refreshShows(
    fetcher: ShowFetcher,
    shows: List<TrackedShow>,
    now: Instant = Instant.now(),
    today: LocalDate = LocalDate.now(),
): RefreshOutcome {
    if (shows.isEmpty()) return RefreshOutcome(shows, emptyList(), emptyMap())

    val fetched = fetcher.fetch(shows.map { it.id })

    val failures = mutableMapOf<Int, Throwable>()
    val discoveries = mutableListOf<Discovery>()

    val updated =
        shows.map { show ->
            val result = fetched[show.id]
            val detail = result?.getOrNull()

            if (detail == null) {
                result?.exceptionOrNull()?.let { failures[show.id] = it }
                return@map show
            }

            val merged = mergeShow(show, detail, now, today)
            findDiscovery(show, merged, today)?.let { discoveries += Discovery(merged, it) }
            merged
        }

    return RefreshOutcome(updated, discoveries, failures)
}
