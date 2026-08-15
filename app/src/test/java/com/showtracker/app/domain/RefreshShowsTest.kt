package com.showtracker.app.domain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.LocalDate

/**
 * The orchestration around `mergeShow` and `findDiscovery`, which the React Native build
 * had as `refreshShows`. Driven through a fake [ShowFetcher], so the failure paths that
 * matter most - the ones a flaky connection produces - are actually exercised rather than
 * hoped about.
 */
class RefreshShowsTest {
    private val today: LocalDate = LocalDate.parse("2026-08-14")
    private val now: Instant = Instant.parse("2026-08-14T10:00:00Z")

    private fun season(
        seasonNumber: Int,
        airDate: String?,
        episodeCount: Int = 10,
    ) = Season(seasonNumber, "Season $seasonNumber", airDate, episodeCount)

    private fun show(
        id: Int,
        seasons: List<Season> = listOf(season(1, "2020-01-01")),
        watchedThroughSeason: Int = 1,
        knownAiredSeason: Int = 1,
    ) = TrackedShow(
        id = id,
        name = "Show $id",
        status = "Returning Series",
        seasons = seasons,
        watchedThroughSeason = watchedThroughSeason,
        knownAiredSeason = knownAiredSeason,
        addedAt = "2026-01-01T00:00:00.000Z",
    )

    private fun detail(
        id: Int,
        seasons: List<Season>,
        name: String = "Show $id",
    ) = ShowDetail(
        id = id,
        name = name,
        overview = "",
        posterPath = null,
        firstAirDate = "2019-01-01",
        status = "Returning Series",
        seasons = seasons,
        lastEpisode = null,
        nextEpisode = null,
    )

    @Test
    fun `does nothing and calls nothing for an empty library`() =
        runTest {
            var called = false
            val outcome =
                refreshShows(
                    ShowFetcher {
                        called = true
                        emptyMap()
                    },
                    emptyList(),
                    now,
                    today,
                )
            assertTrue(outcome.shows.isEmpty())
            assertTrue("an empty library must not hit the network", !called)
        }

    @Test
    fun `asks for exactly the tracked ids`() =
        runTest {
            var asked: List<Int>? = null
            refreshShows(
                ShowFetcher { ids ->
                    asked = ids
                    emptyMap()
                },
                listOf(show(1), show(2), show(3)),
                now,
                today,
            )
            assertEquals(listOf(1, 2, 3), asked)
        }

    @Test
    fun `reports a season that dropped since the last check`() =
        runTest {
            val tracked = show(1)
            val outcome =
                refreshShows(
                    ShowFetcher {
                        mapOf(
                            1 to
                                Result.success(
                                    detail(
                                        1,
                                        listOf(season(1, "2020-01-01"), season(2, "2026-08-01")),
                                    ),
                                ),
                        )
                    },
                    listOf(tracked),
                    now,
                    today,
                )

            assertEquals(1, outcome.discoveries.size)
            assertEquals(
                2,
                outcome.discoveries
                    .single()
                    .season.seasonNumber,
            )
            // The discovery carries the merged show, so a notification can name it.
            assertEquals(
                1,
                outcome.discoveries
                    .single()
                    .show.id,
            )
            assertEquals(2, outcome.shows.single().knownAiredSeason)
        }

    @Test
    fun `keeps the previous data for a show that failed to fetch`() =
        runTest {
            // The whole point: a flaky connection costs freshness, never contents.
            val tracked = show(1)
            val outcome =
                refreshShows(
                    ShowFetcher { mapOf(1 to Result.failure(IOException("no network"))) },
                    listOf(tracked),
                    now,
                    today,
                )

            assertEquals(1, outcome.shows.size)
            assertSame(tracked, outcome.shows.single())
            assertEquals(setOf(1), outcome.failures.keys)
            assertTrue(outcome.discoveries.isEmpty())
        }

    @Test
    fun `keeps a show the fetcher omitted entirely`() =
        runTest {
            // A fetcher that returns a short map must not silently drop the library.
            val outcome =
                refreshShows(
                    ShowFetcher {
                        mapOf(1 to Result.success(detail(1, listOf(season(1, "2020-01-01")))))
                    },
                    listOf(show(1), show(2)),
                    now,
                    today,
                )

            assertEquals(listOf(1, 2), outcome.shows.map { it.id })
            // Absent is not the same as failed: there is no error to report.
            assertTrue(outcome.failures.isEmpty())
        }

    @Test
    fun `one failure does not stop the others updating`() =
        runTest {
            val outcome =
                refreshShows(
                    ShowFetcher {
                        mapOf(
                            1 to Result.failure(IOException("boom")),
                            2 to
                                Result.success(
                                    detail(
                                        2,
                                        listOf(season(1, "2020-01-01"), season(2, "2026-08-01")),
                                    ),
                                ),
                        )
                    },
                    listOf(show(1), show(2)),
                    now,
                    today,
                )

            assertEquals(setOf(1), outcome.failures.keys)
            assertEquals(1, outcome.discoveries.size)
            assertEquals(
                2,
                outcome.discoveries
                    .single()
                    .show.id,
            )
        }

    @Test
    fun `preserves the user's watermark across a refresh`() =
        runTest {
            val tracked = show(1, watchedThroughSeason = 3, knownAiredSeason = 3)
            val outcome =
                refreshShows(
                    ShowFetcher {
                        mapOf(
                            1 to
                                Result.success(
                                    detail(1, listOf(season(1, "2020-01-01")), name = "Renamed"),
                                ),
                        )
                    },
                    listOf(tracked),
                    now,
                    today,
                )

            val merged = outcome.shows.single()
            assertEquals("Renamed", merged.name)
            assertEquals("progress is the user's, not TMDB's", 3, merged.watchedThroughSeason)
        }

    @Test
    fun `stamps the check time on shows that refreshed`() =
        runTest {
            val outcome =
                refreshShows(
                    ShowFetcher {
                        mapOf(1 to Result.success(detail(1, listOf(season(1, "2020-01-01")))))
                    },
                    listOf(show(1)),
                    now,
                    today,
                )
            assertEquals("2026-08-14T10:00:00Z", outcome.shows.single().lastCheckedAt)
        }
}
