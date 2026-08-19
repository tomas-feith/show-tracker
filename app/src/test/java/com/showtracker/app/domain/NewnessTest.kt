package com.showtracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Ported from the React Native build's `src/core/__tests__/newness.test.ts`, case for case.
 * This is the logic the whole app exists to get right, so the suite came across before the
 * screens did.
 */
class NewnessTest {
    private val today: LocalDate = LocalDate.parse("2026-08-14")

    private fun season(
        seasonNumber: Int,
        airDate: String?,
        episodeCount: Int = 10,
    ) = Season(seasonNumber, "Season $seasonNumber", airDate, episodeCount)

    private fun show(
        id: Int = 1,
        name: String = "Test Show",
        status: String = "Returning Series",
        seasons: List<Season> = emptyList(),
        nextEpisode: EpisodeRef? = null,
        watchedThroughSeason: Int = 0,
        inProgressSeason: Int? = null,
        knownAiredSeason: Int = 0,
    ) = TrackedShow(
        id = id,
        name = name,
        firstAirDate = "2019-01-01",
        status = status,
        seasons = seasons,
        nextEpisode = nextEpisode,
        watchedThroughSeason = watchedThroughSeason,
        inProgressSeason = inProgressSeason,
        knownAiredSeason = knownAiredSeason,
        addedAt = "2026-01-01T00:00:00.000Z",
    )

    private fun date(value: String) = LocalDate.parse(value)

    // --- date helpers ---

    @Test
    fun `counts whole days in both directions`() {
        assertEquals(0, daysBetween(date("2026-08-14"), date("2026-08-14")))
        assertEquals(4, daysBetween(date("2026-08-10"), date("2026-08-14")))
        assertEquals(-4, daysBetween(date("2026-08-14"), date("2026-08-10")))
    }

    @Test
    fun `is not skewed across a daylight saving boundary`() {
        // Europe/Lisbon springs forward on 2026-03-29.
        assertEquals(2, daysBetween(date("2026-03-28"), date("2026-03-30")))
    }

    @Test
    fun `treats today as having happened and null as not`() {
        assertTrue(hasHappened(today, today))
        assertTrue(hasHappened(date("2026-08-13"), today))
        assertFalse(hasHappened(date("2026-08-15"), today))
        assertFalse(hasHappened(null, today))
    }

    @Test
    fun `treats an empty or malformed date as absent rather than throwing`() {
        // TMDB returns "" for some missing dates; 2026-02-30 is well-formed but impossible.
        assertNull(parseIsoDate(""))
        assertNull(parseIsoDate(null))
        assertNull(parseIsoDate("not-a-date"))
        assertNull(parseIsoDate("2026-02-30"))
        assertEquals(date("2026-08-14"), parseIsoDate("2026-08-14"))
    }

    // --- latestAiredSeason ---

    @Test
    fun `ignores specials in season 0`() {
        val seasons = listOf(season(0, "2019-01-01"), season(1, "2019-06-01"))
        assertEquals(1, realSeasons(seasons).size)
        assertEquals(1, latestAiredSeason(seasons, today)?.seasonNumber)
    }

    @Test
    fun `ignores a future season that TMDB has already listed`() {
        val seasons = listOf(season(1, "2024-01-01"), season(2, "2027-01-01"))
        assertEquals(1, latestAiredSeason(seasons, today)?.seasonNumber)
    }

    @Test
    fun `ignores an empty placeholder season even with a past air date`() {
        val seasons = listOf(season(1, "2024-01-01"), season(2, "2026-01-01", episodeCount = 0))
        assertEquals(1, latestAiredSeason(seasons, today)?.seasonNumber)
    }

    @Test
    fun `ignores an announced season with no date at all`() {
        val seasons = listOf(season(1, "2024-01-01"), season(2, null))
        assertEquals(1, latestAiredSeason(seasons, today)?.seasonNumber)
    }

    @Test
    fun `returns null when nothing has aired`() {
        assertNull(latestAiredSeason(listOf(season(1, "2030-01-01")), today))
        assertNull(latestAiredSeason(emptyList(), today))
    }

    @Test
    fun `picks the highest number rather than the last in the list`() {
        val seasons =
            listOf(season(3, "2025-01-01"), season(1, "2020-01-01"), season(2, "2022-01-01"))
        assertEquals(3, latestAiredSeason(seasons, today)?.seasonNumber)
    }

    // --- nextUnairedSeason ---

    @Test
    fun `picks the nearest announced but unaired season`() {
        val seasons =
            listOf(season(1, "2020-01-01"), season(2, "2027-01-01"), season(3, "2028-01-01"))
        assertEquals(2, nextUnairedSeason(seasons, today)?.seasonNumber)
    }

    @Test
    fun `is null when every season has aired`() {
        assertNull(nextUnairedSeason(listOf(season(1, "2020-01-01")), today))
    }

    // --- watermark behaviour ---

    @Test
    fun `starts level with the current season so adding a show is quiet`() {
        val seasons =
            listOf(season(1, "2020-01-01"), season(2, "2021-01-01"), season(3, "2022-01-01"))
        val watermark = initialWatermark(seasons, today)
        assertEquals(3, watermark)
        assertFalse(isBehind(show(seasons = seasons, watchedThroughSeason = watermark), today))
    }

    @Test
    fun `reports a new season once one airs above the watermark`() {
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2026-02-01"))
        assertTrue(isBehind(show(seasons = seasons, watchedThroughSeason = 1), today))
    }

    @Test
    fun `does not report an unreleased season as new`() {
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2027-02-01"))
        assertFalse(isBehind(show(seasons = seasons, watchedThroughSeason = 1), today))
    }

    @Test
    fun `handles a show with no aired seasons at all`() {
        assertEquals(0, initialWatermark(emptyList(), today))
        assertFalse(isBehind(show(seasons = emptyList()), today))
    }

    // --- seasonsBehind ---

    private val backlogSeasons =
        listOf(
            season(0, "2019-01-01"), // specials, never counted
            season(1, "2020-01-01"),
            season(2, "2021-01-01"),
            season(3, "2022-01-01"),
            season(4, "2027-01-01"), // announced but not aired
        )

    @Test
    fun `counts only aired seasons above what the user watched`() {
        assertEquals(
            2,
            seasonsBehind(show(seasons = backlogSeasons, watchedThroughSeason = 1), today),
        )
        assertEquals(
            1,
            seasonsBehind(show(seasons = backlogSeasons, watchedThroughSeason = 2), today),
        )
        assertEquals(
            0,
            seasonsBehind(show(seasons = backlogSeasons, watchedThroughSeason = 3), today),
        )
    }

    @Test
    fun `counts every aired season when the user has not started`() {
        assertEquals(
            3,
            seasonsBehind(show(seasons = backlogSeasons, watchedThroughSeason = 0), today),
        )
    }

    @Test
    fun `never goes negative if the watermark outruns the season list`() {
        // Possible if TMDB withdraws a season the user had already marked watched.
        assertEquals(
            0,
            seasonsBehind(show(seasons = backlogSeasons, watchedThroughSeason = 99), today),
        )
    }

    @Test
    fun `agrees with hasAired about what counts`() {
        assertFalse(hasAired(season(4, "2027-01-01"), today))
        assertFalse(hasAired(season(0, "2019-01-01"), today))
        assertFalse(hasAired(season(2, "2021-01-01", episodeCount = 0), today))
        assertTrue(hasAired(season(2, "2021-01-01"), today))
    }

    // --- showState ---

    @Test
    fun `reports how many seasons deep the backlog is`() {
        val state =
            showState(
                show(
                    seasons =
                        listOf(
                            season(1, "2020-01-01"),
                            season(2, "2021-01-01"),
                            season(3, "2026-06-01"),
                        ),
                    watchedThroughSeason = 0,
                ),
                today,
            )
        assertTrue(state is ShowState.Behind)
        state as ShowState.Behind
        assertEquals(3, state.seasonsBehind)
        assertEquals(3, state.latest.seasonNumber)
    }

    @Test
    fun `keeps reporting the next episode deep into a season's run`() {
        // Deliberately changed from the React Native build, which called this "Season 2 out
        // 2 months ago" - an unseen aired season always outranked an upcoming episode
        // there. A season 74 days into its run is still releasing, and the episode due is
        // the useful line; the pile-up is only a backlog once the run ends. An older
        // unwatched season still takes the headline back, which the tests below cover.
        val nextEpisode = EpisodeRef(2, 8, "Later", "2026-09-01")
        val state =
            showState(
                show(
                    seasons = listOf(season(1, "2020-01-01"), season(2, "2026-06-01")),
                    watchedThroughSeason = 1,
                    nextEpisode = nextEpisode,
                ),
                today,
            )

        assertEquals(ShowState.Airing(nextEpisode, 18), state)
    }

    @Test
    fun `reports an airing show by its next episode`() {
        val next = EpisodeRef(1, 3, "Next", "2026-08-21")
        val state =
            showState(
                show(
                    seasons = listOf(season(1, "2026-08-01")),
                    watchedThroughSeason = 1,
                    nextEpisode = next,
                ),
                today,
            )
        assertEquals(ShowState.Airing(next, 7), state)
    }

    @Test
    fun `falls back to an announced season when no episode is scheduled`() {
        val state =
            showState(
                show(
                    seasons = listOf(season(1, "2020-01-01"), season(2, "2026-11-01")),
                    watchedThroughSeason = 1,
                ),
                today,
            )
        assertTrue(state is ShowState.Upcoming)
    }

    @Test
    fun `marks finished shows as ended`() {
        val state =
            showState(
                show(
                    seasons = listOf(season(1, "2020-01-01")),
                    watchedThroughSeason = 1,
                    status = "Ended",
                ),
                today,
            )
        assertEquals(ShowState.Ended, state)
    }

    @Test
    fun `marks a returning show with nothing scheduled as waiting`() {
        val state =
            showState(
                show(seasons = listOf(season(1, "2020-01-01")), watchedThroughSeason = 1),
                today,
            )
        assertEquals(ShowState.Waiting, state)
    }

    @Test
    fun `treats In Production as waiting rather than ended`() {
        // A fourth status value that appears in the real library. Only Ended and Canceled
        // are terminal; anything else is a show that may yet return.
        val state =
            showState(
                show(
                    seasons = listOf(season(1, "2020-01-01")),
                    watchedThroughSeason = 1,
                    status = "In Production",
                ),
                today,
            )
        assertEquals(ShowState.Waiting, state)
    }

    @Test
    fun `does not treat a past next-episode marker as upcoming`() {
        // TMDB occasionally leaves a stale next_episode_to_air behind.
        val state =
            showState(
                show(
                    seasons = listOf(season(1, "2020-01-01")),
                    watchedThroughSeason = 1,
                    status = "Ended",
                    nextEpisode = EpisodeRef(1, 9, "Stale", "2020-05-01"),
                ),
                today,
            )
        assertEquals(ShowState.Ended, state)
    }

    // --- sortLibrary ---

    @Test
    fun `floats new seasons to the top and ended shows to the bottom`() {
        val shows =
            listOf(
                show(
                    id = 1,
                    name = "Ended Show",
                    seasons = listOf(season(1, "2019-01-01")),
                    watchedThroughSeason = 1,
                    status = "Ended",
                ),
                show(
                    id = 2,
                    name = "Airing Show",
                    seasons = listOf(season(1, "2026-08-01")),
                    watchedThroughSeason = 1,
                    nextEpisode = EpisodeRef(1, 3, "x", "2026-08-20"),
                ),
                show(
                    id = 3,
                    name = "New Season Show",
                    seasons = listOf(season(1, "2020-01-01"), season(2, "2026-03-01")),
                    watchedThroughSeason = 1,
                ),
            )
        assertEquals(listOf(3, 2, 1), sortLibrary(shows, today).map { it.id })
    }

    @Test
    fun `orders multiple new seasons by most recent drop first`() {
        val shows =
            listOf(
                show(
                    id = 1,
                    name = "Older",
                    seasons = listOf(season(1, "2019-01-01"), season(2, "2026-01-01")),
                    watchedThroughSeason = 1,
                ),
                show(
                    id = 2,
                    name = "Newer",
                    seasons = listOf(season(1, "2019-01-01"), season(2, "2026-08-01")),
                    watchedThroughSeason = 1,
                ),
            )
        assertEquals(listOf(2, 1), sortLibrary(shows, today).map { it.id })
    }

    @Test
    fun `does not mutate the input list`() {
        val shows = listOf(show(id = 1, name = "B"), show(id = 2, name = "A"))
        val original = shows.toList()
        sortLibrary(shows, today)
        assertEquals(original, shows)
    }

    @Test
    fun `orders accented names where a reader expects them, not by code unit`() {
        // "Glória" must file under G, before "Grimm". Natural String ordering puts U+00F3
        // above every ASCII letter and would sort it after.
        val shows =
            listOf(
                show(id = 1, name = "Grimm", status = "Ended"),
                show(id = 2, name = "Glória", status = "Ended"),
            )
        assertEquals(listOf(2, 1), sortLibrary(shows, today).map { it.id })
    }

    // --- adding a show ---

    /**
     * Reacher, as reported: season 4 premiered on 1 August with 8 episodes, two have aired
     * and the third is due. Adding the show here must not claim season 4 was watched.
     */
    private val airingSeasons =
        listOf(
            season(1, "2022-02-04", episodeCount = 8),
            season(2, "2023-12-15", episodeCount = 8),
            season(3, "2025-02-20", episodeCount = 8),
            season(4, "2026-08-01", episodeCount = 8),
        )
    private val airedSoFar = EpisodeRef(4, 2, "Episode 2", "2026-08-08")
    private val dueNext = EpisodeRef(4, 3, "Episode 3", "2026-08-15")

    @Test
    fun `does not call a season watched while it is still releasing episodes`() {
        assertEquals(
            3,
            initialWatchedThrough(airingSeasons, airedSoFar, dueNext, today),
        )
    }

    @Test
    fun `still records the airing season as announced, so it is not reported as news`() {
        // Lowering this one too would announce season 4 on the next refresh, as though it
        // had just dropped.
        assertEquals(4, initialWatermark(airingSeasons, today))
    }

    @Test
    fun `assumes a finished season was watched, as following a show always has`() {
        val finished = airingSeasons.dropLast(1)
        assertEquals(
            3,
            initialWatchedThrough(finished, EpisodeRef(3, 8, "Finale", "2025-04-10"), null, today),
        )
    }

    @Test
    fun `spots a run in progress when the next episode marker is missing`() {
        // TMDB clears next_episode_to_air in the gap between episodes as well as after a
        // finale; the episode count is what separates the two.
        assertEquals(
            3,
            initialWatchedThrough(airingSeasons, airedSoFar, null, today),
        )
    }

    @Test
    fun `treats a season whose finale has aired as finished`() {
        val finale = EpisodeRef(4, 8, "Episode 8", "2026-08-13")
        assertEquals(
            4,
            initialWatchedThrough(airingSeasons, finale, null, today),
        )
    }

    @Test
    fun `does not go below not-started for a first season still airing`() {
        val firstRun = listOf(season(1, "2026-08-01", episodeCount = 8))
        val partway = EpisodeRef(1, 2, "Episode 2", "2026-08-08")
        assertEquals(0, initialWatchedThrough(firstRun, partway, null, today))
    }

    @Test
    fun `a show added mid-season still reads as backlog once the finale airs`() {
        // The reported symptom: the green airing label used to hide a watermark that had
        // already claimed the season, and its disappearance looked like the season being
        // marked watched.
        val added =
            show(
                seasons = airingSeasons,
                watchedThroughSeason =
                    initialWatchedThrough(airingSeasons, airedSoFar, dueNext, today),
                knownAiredSeason = initialWatermark(airingSeasons, today),
            ).copy(lastEpisode = airedSoFar, nextEpisode = null)

        val afterFinale = date("2026-09-20")
        assertEquals(1, seasonsBehind(added, afterFinale))
        assertTrue(showState(added, afterFinale) is ShowState.Behind)
    }

    @Test
    fun `shows the next episode, not a backlog, while a season is still dropping`() {
        // Lanterns, as reported: season 1 premiered three days ago and runs weekly. It is
        // a show to keep up with, not a backlog to clear.
        val weekly = listOf(season(1, "2026-08-11", episodeCount = 8))
        val due = EpisodeRef(1, 2, "Episode 2", "2026-08-18")
        val lanterns =
            show(seasons = weekly, watchedThroughSeason = 0)
                .copy(lastEpisode = EpisodeRef(1, 1, "Episode 1", "2026-08-11"), nextEpisode = due)

        assertEquals(ShowState.Airing(due, 4), showState(lanterns, today))
    }

    @Test
    fun `a deeper backlog still outranks a run in progress`() {
        // Three unwatched seasons with the newest still airing: the depth is the honest
        // headline, not the episode due on Thursday.
        val due = EpisodeRef(4, 3, "Episode 3", "2026-08-15")
        val neglected =
            show(seasons = airingSeasons, watchedThroughSeason = 1)
                .copy(lastEpisode = airedSoFar, nextEpisode = due)

        val state = showState(neglected, today)
        assertTrue(state is ShowState.Behind)
        assertEquals(3, (state as ShowState.Behind).seasonsBehind)
    }

    @Test
    fun `an announced episode of a later season does not mask an unwatched season`() {
        // next_episode_to_air belongs to season 4 while season 3 sits unwatched: the
        // backlog is what matters, not the premiere of something newer.
        val premiere = EpisodeRef(4, 1, "Episode 1", "2026-08-20")
        val stale =
            show(seasons = airingSeasons.dropLast(1), watchedThroughSeason = 2)
                .copy(nextEpisode = premiere)

        assertTrue(showState(stale, today) is ShowState.Behind)
    }

    // --- in progress ---

    @Test
    fun `resolves the season the user is partway through`() {
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2021-01-01"))
        val resolved = seasonInProgress(show(seasons = seasons, inProgressSeason = 2), today)
        assertEquals(2, resolved?.seasonNumber)
    }

    @Test
    fun `has nothing in progress without a marker`() {
        val seasons = listOf(season(1, "2020-01-01"))
        assertNull(seasonInProgress(show(seasons = seasons), today))
    }

    @Test
    fun `ignores a marker on a season already watched through`() {
        // The marker outlives the watermark move in an old row, or in an imported file
        // written before the two were kept consistent. A finished season is not underway.
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2021-01-01"))
        assertNull(
            seasonInProgress(
                show(seasons = seasons, watchedThroughSeason = 2, inProgressSeason = 2),
                today,
            ),
        )
    }

    @Test
    fun `ignores a marker on a season TMDB no longer lists`() {
        val seasons = listOf(season(1, "2020-01-01"))
        assertNull(seasonInProgress(show(seasons = seasons, inProgressSeason = 4), today))
    }

    @Test
    fun `ignores a marker on a season that has not aired yet`() {
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2026-12-01"))
        assertNull(seasonInProgress(show(seasons = seasons, inProgressSeason = 2), today))
    }

    @Test
    fun `a season in progress outranks the backlog it sits in`() {
        val seasons =
            listOf(
                season(1, "2020-01-01"),
                season(2, "2021-01-01"),
                season(3, "2022-01-01"),
            )
        val state =
            showState(
                show(seasons = seasons, watchedThroughSeason = 0, inProgressSeason = 1),
                today,
            )

        assertTrue(state is ShowState.Watching)
        state as ShowState.Watching
        assertEquals(1, state.season.seasonNumber)
        // Seasons 2 and 3 are still waiting once season 1 is finished.
        assertEquals(2, state.seasonsWaiting)
    }

    @Test
    fun `counts a backlog below the season in progress as still waiting`() {
        // Someone who skips ahead to the newest season still has the older ones waiting.
        // Counting only what sits above season 3 would report nothing left.
        val seasons =
            listOf(
                season(1, "2020-01-01"),
                season(2, "2021-01-01"),
                season(3, "2022-01-01"),
            )
        val state =
            showState(
                show(seasons = seasons, watchedThroughSeason = 0, inProgressSeason = 3),
                today,
            )

        assertEquals(ShowState.Watching(seasons[2], 2), state)
    }

    @Test
    fun `never reports a negative remainder`() {
        val seasons = listOf(season(1, "2020-01-01"))
        val state =
            showState(
                show(seasons = seasons, watchedThroughSeason = 0, inProgressSeason = 1),
                today,
            )

        assertEquals(ShowState.Watching(seasons[0], 0), state)
    }

    @Test
    fun `a season in progress with nothing above it reports no remainder`() {
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2021-01-01"))
        val state =
            showState(
                show(seasons = seasons, watchedThroughSeason = 1, inProgressSeason = 2),
                today,
            )

        assertEquals(ShowState.Watching(seasons[1], 0), state)
    }

    @Test
    fun `a stale marker falls back to the state it would otherwise have`() {
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2021-01-01"))
        val state =
            showState(
                show(seasons = seasons, watchedThroughSeason = 0, inProgressSeason = 9),
                today,
            )

        assertTrue(state is ShowState.Behind)
    }

    @Test
    fun `sorts shows in progress above shows merely behind`() {
        val seasons = listOf(season(1, "2020-01-01"), season(2, "2026-08-01"))
        val shows =
            listOf(
                // Two seasons behind, and a season that dropped a fortnight ago.
                show(id = 1, name = "Behind", seasons = seasons),
                show(
                    id = 2,
                    name = "Underway",
                    seasons = seasons,
                    inProgressSeason = 1,
                ),
            )

        assertEquals(listOf(2, 1), sortLibrary(shows, today).map { it.id })
    }

    // --- formatting ---

    @Test
    fun `describes day counts in human terms`() {
        assertEquals("today", describeDays(0, Direction.AGO))
        assertEquals("yesterday", describeDays(1, Direction.AGO))
        assertEquals("5 days ago", describeDays(5, Direction.AGO))
        assertEquals("6 months ago", describeDays(180, Direction.AGO))
        assertEquals("over a year ago", describeDays(400, Direction.AGO))
        assertEquals("tomorrow", describeDays(1, Direction.UNTIL))
        assertEquals("in 10 days", describeDays(10, Direction.UNTIL))
    }

    @Test
    fun `rounds months rather than truncating them`() {
        // Integer division would call 175 days "5 months"; the JavaScript this replaces
        // used Math.round, so it is 6.
        assertEquals("6 months ago", describeDays(175, Direction.AGO))
        assertEquals("in 2 months", describeDays(45, Direction.UNTIL))
    }

    @Test
    fun `zero-pads episode codes`() {
        assertEquals("S02E05", formatEpisode(EpisodeRef(2, 5, "", null)))
        assertEquals("S12E134", formatEpisode(EpisodeRef(12, 134, "", null)))
    }
}
