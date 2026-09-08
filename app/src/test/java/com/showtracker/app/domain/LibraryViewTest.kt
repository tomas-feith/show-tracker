package com.showtracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The orders and filters behind the library screen's two controls.
 *
 * Every case here is one a real library produces: a show TMDB has no score for, one it has
 * no runtime for, one announced without a date. Each of those is the input that decides
 * whether a filter answers the question asked or quietly pads the list.
 */
class LibraryViewTest {
    private val today: LocalDate = LocalDate.parse("2026-08-14")

    private fun show(
        id: Int,
        name: String,
        firstAirDate: String? = "2020-01-01",
        voteAverage: Double = 0.0,
        voteCount: Int = 0,
        episodeRunTime: Int? = null,
        genres: List<String> = emptyList(),
        seasons: List<Season> = listOf(Season(1, "Season 1", "2020-01-01", 10)),
        watchedThroughSeason: Int = 0,
        status: String = "Returning Series",
        favourite: Boolean = false,
    ) = TrackedShow(
        id = id,
        name = name,
        firstAirDate = firstAirDate,
        status = status,
        seasons = seasons,
        watchedThroughSeason = watchedThroughSeason,
        voteAverage = voteAverage,
        voteCount = voteCount,
        genres = genres,
        episodeRunTime = episodeRunTime,
        favourite = favourite,
    )

    private fun names(shows: List<TrackedShow>) = shows.map { it.name }

    // --- sorting ---

    @Test
    fun `orders by name using the accent-aware collator`() {
        val shows = listOf(show(1, "Grimm"), show(2, "Glória"), show(3, "Andor"))
        assertEquals(
            // Not UTF-16 order, which would file "Glória" after "Grimm".
            listOf("Andor", "Glória", "Grimm"),
            names(sortLibrary(shows, today, LibrarySort.NAME)),
        )
    }

    @Test
    fun `orders by release date, newest first, undated last`() {
        val shows =
            listOf(
                show(1, "Old", firstAirDate = "2011-04-17"),
                show(2, "Unscheduled", firstAirDate = null),
                show(3, "New", firstAirDate = "2024-02-27"),
            )
        assertEquals(
            listOf("New", "Old", "Unscheduled"),
            names(sortLibrary(shows, today, LibrarySort.RELEASED)),
        )
    }

    @Test
    fun `orders by score, and puts the unrated below every real score`() {
        val shows =
            listOf(
                show(1, "Unrated"),
                show(2, "Good", voteAverage = 7.5, voteCount = 900),
                show(3, "Great", voteAverage = 8.9, voteCount = 1200),
            )
        // Not level with 0.0: an unrated show is one nobody has judged, and 0.0 is also
        // what every row holds before its first refresh.
        assertEquals(
            listOf("Great", "Good", "Unrated"),
            names(sortLibrary(shows, today, LibrarySort.SCORE)),
        )
    }

    @Test
    fun `orders by episode length, shortest first, unknown last`() {
        val shows =
            listOf(
                show(1, "Unknown"),
                show(2, "Drama", episodeRunTime = 55),
                show(3, "Sitcom", episodeRunTime = 22),
            )
        assertEquals(
            listOf("Sitcom", "Drama", "Unknown"),
            names(sortLibrary(shows, today, LibrarySort.RUNTIME)),
        )
    }

    @Test
    fun `orders by backlog, deepest first`() {
        val three =
            listOf(
                Season(1, "S1", "2020-01-01", 10),
                Season(2, "S2", "2021-01-01", 10),
                Season(3, "S3", "2022-01-01", 10),
            )
        val shows =
            listOf(
                show(1, "CaughtUp", seasons = three, watchedThroughSeason = 3),
                show(2, "Deep", seasons = three, watchedThroughSeason = 0),
                show(3, "Shallow", seasons = three, watchedThroughSeason = 2),
            )
        assertEquals(
            listOf("Deep", "Shallow", "CaughtUp"),
            names(sortLibrary(shows, today, LibrarySort.BACKLOG)),
        )
    }

    @Test
    fun `breaks every tie on the name, so no order depends on row order`() {
        val a = show(1, "Beta", voteAverage = 8.0, voteCount = 10)
        val b = show(2, "Alpha", voteAverage = 8.0, voteCount = 10)

        // The same pair handed over in either order must come back the same way round.
        assertEquals(
            listOf("Alpha", "Beta"),
            names(sortLibrary(listOf(a, b), today, LibrarySort.SCORE)),
        )
        assertEquals(
            listOf("Alpha", "Beta"),
            names(sortLibrary(listOf(b, a), today, LibrarySort.SCORE)),
        )
    }

    @Test
    fun `counts an unwatched aired season as remaining, and an unaired one as not`() {
        val seasons =
            listOf(
                Season(0, "Specials", "2020-01-01", 3),
                Season(1, "S1", "2020-01-01", 10),
                Season(2, "S2", "2027-01-01", 10),
            )
        // Specials are nobody's backlog, and a season that has not aired is not waiting.
        assertEquals(1, seasonsRemaining(show(1, "X", seasons = seasons), today))
    }

    // --- filtering ---

    @Test
    fun `an empty filter is the whole library`() {
        val shows = listOf(show(1, "A"), show(2, "B"))
        assertEquals(shows, applyFilters(shows, LibraryFilters(), today))
    }

    @Test
    fun `combines genres with or, and axes with and`() {
        val shows =
            listOf(
                show(1, "ShortDrama", genres = listOf("Drama"), episodeRunTime = 25),
                show(2, "LongDrama", genres = listOf("Drama"), episodeRunTime = 55),
                show(3, "ShortComedy", genres = listOf("Comedy"), episodeRunTime = 25),
                show(4, "ShortDoc", genres = listOf("Documentary"), episodeRunTime = 25),
            )

        val result =
            applyFilters(
                shows,
                LibraryFilters(
                    genres = setOf("Drama", "Comedy"),
                    runtime = RuntimeBand.SHORT,
                ),
                today,
            )

        // Either genre, and also short: that is what picking two genres and a length means.
        assertEquals(listOf("ShortDrama", "ShortComedy"), names(result))
    }

    @Test
    fun `hides what TMDB has no runtime or score for while those filters are on`() {
        val shows =
            listOf(
                show(1, "Known", episodeRunTime = 25, voteAverage = 8.5, voteCount = 100),
                show(2, "Unknown"),
            )

        assertEquals(
            listOf("Known"),
            names(applyFilters(shows, LibraryFilters(runtime = RuntimeBand.SHORT), today)),
        )
        // A show with no score is not a show above 8; answering otherwise would pad the
        // list with exactly what was filtered out.
        assertEquals(
            listOf("Known"),
            names(applyFilters(shows, LibraryFilters(score = ScoreFloor.GREAT), today)),
        )
    }

    @Test
    fun `bands cover every runtime exactly once`() {
        // No minute may fall in two bands or in none, or the three of them together would
        // hide or double-count shows.
        (1..200).forEach { minutes ->
            assertEquals(
                "$minutes minutes",
                1,
                RuntimeBand.entries.count { it.matches(minutes) },
            )
        }
    }

    @Test
    fun `groups a show by the state its row already shows`() {
        val behind =
            show(
                1,
                "Behind",
                seasons =
                    listOf(
                        Season(1, "S1", "2020-01-01", 10),
                        Season(2, "S2", "2026-07-01", 10),
                    ),
            )
        // Watched through, or it would be Behind: a finished show with a season still
        // waiting is backlog first and ended second, which is what the row already says.
        val ended = show(2, "Ended", status = "Ended", watchedThroughSeason = 1)

        assertEquals(StateGroup.BEHIND, stateGroup(behind, today))
        assertEquals(StateGroup.ENDED, stateGroup(ended, today))
        assertEquals(
            listOf("Behind"),
            names(
                applyFilters(
                    listOf(behind, ended),
                    LibraryFilters(states = setOf(StateGroup.BEHIND)),
                    today,
                ),
            ),
        )
    }

    @Test
    fun `keeps only starred shows while the favourites filter is on`() {
        val shows =
            listOf(
                show(1, "Starred", favourite = true),
                show(2, "Plain"),
            )

        assertEquals(
            listOf("Starred"),
            names(applyFilters(shows, LibraryFilters(favouritesOnly = true), today)),
        )
        // And it composes with the other axes rather than replacing them.
        assertEquals(
            emptyList<String>(),
            names(
                applyFilters(
                    shows,
                    LibraryFilters(favouritesOnly = true, score = ScoreFloor.GOOD),
                    today,
                ),
            ),
        )
    }

    @Test
    fun `lists the genres the library actually has, alphabetically and once each`() {
        val shows =
            listOf(
                show(1, "A", genres = listOf("Drama", "Comedy")),
                show(2, "B", genres = listOf("Comedy")),
                show(3, "C"),
            )
        // Built from the shows, so the menu can never offer a filter that empties the list.
        assertEquals(listOf("Comedy", "Drama"), libraryGenres(shows))
    }

    @Test
    fun `reports whether anything is being hidden`() {
        assertEquals(false, LibraryFilters().active)
        assertTrue(LibraryFilters(genres = setOf("Drama")).active)
        assertTrue(LibraryFilters(score = ScoreFloor.GOOD).active)
        // The star hides shows like any other axis, so the screen has to disclose it too.
        assertTrue(LibraryFilters(favouritesOnly = true).active)
    }
}
