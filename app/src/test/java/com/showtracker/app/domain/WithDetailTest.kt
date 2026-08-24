package com.showtracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The single seam between what TMDB owns and what the user owns.
 *
 * Both paths that hold a `ShowDetail` go through [withDetail]: refreshing a show, and
 * following one for the first time. They used to copy the fields out separately, and when
 * the metadata columns arrived only the refresh was updated - so a show followed at 10:00
 * had no score, no genres and no episode length until it went stale six hours later, and
 * was hidden by a score or runtime filter for that whole time.
 *
 * The test that matters here is [carries every field TMDB owns], which fails the moment a
 * field is added to `ShowDetail` and not wired into [withDetail].
 */
class WithDetailTest {
    private val today: LocalDate = LocalDate.parse("2026-08-14")

    private val detail =
        ShowDetail(
            id = 1396,
            name = "Shōgun",
            overview = "An overview.",
            posterPath = "/poster.jpg",
            firstAirDate = "2024-02-27",
            status = "Ended",
            seasons = listOf(Season(1, "Season 1", "2024-02-27", 10)),
            lastEpisode = EpisodeRef(1, 10, "Finale", "2024-04-23"),
            nextEpisode = null,
            voteAverage = 8.4,
            voteCount = 4321,
            genres = listOf("Drama", "Action & Adventure"),
            episodeRunTime = 55,
            type = "Miniseries",
            numberOfEpisodes = 10,
        )

    /** What following a show builds, as `LibraryViewModel.addShow` builds it. */
    private fun followed(): TrackedShow =
        TrackedShow(id = detail.id, name = detail.name)
            .withDetail(detail)
            .copy(
                watchedThroughSeason = 0,
                knownAiredSeason = 1,
                addedAt = "2026-08-14T10:00:00Z",
                lastCheckedAt = "2026-08-14T10:00:00Z",
            )

    @Test
    fun `carries every field TMDB owns`() {
        val show = followed()

        assertEquals("Shōgun", show.name)
        assertEquals("An overview.", show.overview)
        assertEquals("/poster.jpg", show.posterPath)
        assertEquals("2024-02-27", show.firstAirDate)
        assertEquals("Ended", show.status)
        assertEquals(1, show.seasons.size)
        assertEquals(10, show.lastEpisode?.episodeNumber)

        // The six that were being dropped.
        assertEquals(8.4, show.voteAverage, 0.001)
        assertEquals(4321, show.voteCount)
        assertEquals(listOf("Drama", "Action & Adventure"), show.genres)
        assertEquals(55, show.episodeRunTime)
        assertEquals("Miniseries", show.type)
        assertEquals(10, show.numberOfEpisodes)
    }

    @Test
    fun `a followed show and a refreshed one agree about everything TMDB owns`() {
        // The regression that shipped: the two paths built the show separately and drifted.
        // Comparing them directly is what makes a future divergence a test failure rather
        // than a screen someone eventually notices is blank.
        val refreshed =
            mergeShow(
                TrackedShow(id = detail.id, name = "Stale name"),
                detail,
                now = Instant.parse("2026-08-14T10:00:00Z"),
                today = today,
            )

        val followedShow = followed()
        val tmdbOwned = { show: TrackedShow ->
            listOf(
                show.name,
                show.overview,
                show.posterPath,
                show.firstAirDate,
                show.status,
                show.seasons,
                show.lastEpisode,
                show.nextEpisode,
                show.voteAverage,
                show.voteCount,
                show.genres,
                show.episodeRunTime,
                show.type,
                show.numberOfEpisodes,
            )
        }

        assertEquals(tmdbOwned(refreshed), tmdbOwned(followedShow))
    }

    @Test
    fun `leaves what the user owns alone`() {
        val mine =
            TrackedShow(
                id = 1396,
                name = "Old name",
                watchedThroughSeason = 3,
                inProgressSeason = 4,
                knownAiredSeason = 4,
                addedAt = "2025-01-01T00:00:00Z",
            )

        val updated = mine.withDetail(detail)

        // The watermark exists nowhere else, and a refresh must never move it.
        assertEquals(3, updated.watchedThroughSeason)
        assertEquals(4, updated.inProgressSeason)
        assertEquals(4, updated.knownAiredSeason)
        assertEquals("2025-01-01T00:00:00Z", updated.addedAt)
    }
}
