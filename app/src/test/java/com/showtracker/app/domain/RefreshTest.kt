package com.showtracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** Ported from the React Native build's `src/core/__tests__/refresh.test.ts`. */
class RefreshTest {
    private val today: LocalDate = LocalDate.parse("2026-08-14")

    private fun season(
        seasonNumber: Int,
        airDate: String?,
        episodeCount: Int = 10,
    ) = Season(seasonNumber, "Season $seasonNumber", airDate, episodeCount)

    private fun show(
        name: String = "Test Show",
        status: String = "Returning Series",
        seasons: List<Season> = emptyList(),
        watchedThroughSeason: Int = 0,
        knownAiredSeason: Int = 0,
        addedAt: String = "2026-01-01T00:00:00.000Z",
    ) = TrackedShow(
        id = 1,
        name = name,
        firstAirDate = "2019-01-01",
        status = status,
        seasons = seasons,
        watchedThroughSeason = watchedThroughSeason,
        knownAiredSeason = knownAiredSeason,
        addedAt = addedAt,
    )

    private fun detail(
        name: String = "Test Show",
        status: String = "Returning Series",
        seasons: List<Season> = emptyList(),
    ) = ShowDetail(
        id = 1,
        name = name,
        overview = "An overview.",
        posterPath = "/p.jpg",
        firstAirDate = "2019-01-01",
        status = status,
        seasons = seasons,
        lastEpisode = null,
        nextEpisode = null,
    )

    // --- mergeShow ---

    @Test
    fun `preserves the user watermark and added date`() {
        val existing = show(watchedThroughSeason = 4, addedAt = "2025-05-05T00:00:00.000Z")
        val merged =
            mergeShow(existing, detail(seasons = listOf(season(5, "2026-01-01"))), today = today)
        assertEquals(4, merged.watchedThroughSeason)
        assertEquals("2025-05-05T00:00:00.000Z", merged.addedAt)
    }

    @Test
    fun `takes fresh metadata from TMDB`() {
        val merged =
            mergeShow(
                show(name = "Old Name", status = "Returning Series"),
                detail(
                    name = "New Name",
                    status = "Ended",
                    seasons = listOf(season(1, "2020-01-01")),
                ),
                now = Instant.parse("2026-08-14T10:00:00Z"),
                today = today,
            )
        assertEquals("New Name", merged.name)
        assertEquals("Ended", merged.status)
        assertEquals(1, merged.seasons.size)
        assertEquals("2026-08-14T10:00:00Z", merged.lastCheckedAt)
    }

    // --- findDiscovery ---

    @Test
    fun `announces a season that appeared since the last check`() {
        val before =
            show(
                seasons = listOf(season(1, "2020-01-01")),
                watchedThroughSeason = 1,
                knownAiredSeason = 1,
            )
        val after =
            before.copy(seasons = listOf(season(1, "2020-01-01"), season(2, "2026-08-01")))
        assertEquals(2, findDiscovery(before, after, today)?.seasonNumber)
    }

    @Test
    fun `announces a season TMDB listed early once its air date arrives`() {
        // The regression that matters. TMDB announced season 2 months ahead, so it was
        // already in the stored season list with a future date. Today that date passed.
        // Nothing about the data changed - only the calendar did - and this is exactly the
        // "new season dropped and I never heard" case the app exists to prevent.
        val stored = listOf(season(1, "2020-01-01"), season(2, "2026-08-10"))
        val before = show(seasons = stored, watchedThroughSeason = 1, knownAiredSeason = 1)
        val after = before.copy(seasons = stored)
        assertEquals(2, findDiscovery(before, after, today)?.seasonNumber)
    }

    @Test
    fun `stays quiet once that season has been recorded as known`() {
        // The refresh that announced it also raised knownAiredSeason, so the next refresh
        // must not announce the same season again.
        val stored = listOf(season(1, "2020-01-01"), season(2, "2026-08-10"))
        val before = show(seasons = stored, watchedThroughSeason = 1, knownAiredSeason = 2)
        assertNull(findDiscovery(before, before.copy(), today))
    }

    @Test
    fun `stays quiet when the new season is already acknowledged`() {
        val before =
            show(
                seasons = listOf(season(1, "2020-01-01")),
                watchedThroughSeason = 2,
                knownAiredSeason = 1,
            )
        val after =
            before.copy(seasons = listOf(season(1, "2020-01-01"), season(2, "2026-08-01")))
        assertNull(findDiscovery(before, after, today))
    }

    @Test
    fun `stays quiet when the newly listed season has not aired yet`() {
        val before =
            show(
                seasons = listOf(season(1, "2020-01-01")),
                watchedThroughSeason = 1,
                knownAiredSeason = 1,
            )
        val after =
            before.copy(seasons = listOf(season(1, "2020-01-01"), season(2, "2027-01-01")))
        assertNull(findDiscovery(before, after, today))
    }

    @Test
    fun `announces the first ever aired season of a newly started show`() {
        val before = show(seasons = listOf(season(1, "2027-01-01")), watchedThroughSeason = 0)
        val after = before.copy(seasons = listOf(season(1, "2026-08-01")))
        assertEquals(1, findDiscovery(before, after, today)?.seasonNumber)
    }

    // --- the announce-once cycle end to end ---

    @Test
    fun `announces a newly aired season exactly once across repeated refreshes`() {
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2026-08-10"))
        // Followed back when only season 1 had aired.
        var tracked =
            show(
                seasons = listOf(season(1, "2020-01-01")),
                watchedThroughSeason = 1,
                knownAiredSeason = 1,
            )

        val first =
            mergeShow(
                tracked,
                detail(seasons = seasons),
                now = Instant.parse("2026-08-14T09:00:00Z"),
                today = today,
            )
        assertEquals(2, findDiscovery(tracked, first, today)?.seasonNumber)
        assertEquals(2, first.knownAiredSeason)

        tracked = first
        val second =
            mergeShow(
                tracked,
                detail(seasons = seasons),
                now = Instant.parse("2026-08-14T21:00:00Z"),
                today = today,
            )
        assertNull(findDiscovery(tracked, second, today))
    }
}
