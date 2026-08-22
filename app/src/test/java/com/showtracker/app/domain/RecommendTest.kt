package com.showtracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendTest {
    private fun show(
        id: Int,
        name: String = "Show $id",
        voteAverage: Double = 7.0,
        voteCount: Int = 500,
    ) = SearchResult(
        id = id,
        name = name,
        overview = "",
        posterPath = null,
        firstAirDate = "2020-01-01",
        voteAverage = voteAverage,
        voteCount = voteCount,
    )

    @Test
    fun `ranks a show recommended by several seeds above one recommended by a single seed`() {
        val ranked =
            rankRecommendations(
                listOf(
                    SeededResults("Severance", listOf(show(1), show(2))),
                    SeededResults("Dark", listOf(show(2), show(3))),
                ),
            )

        assertEquals(listOf(2, 1, 3), ranked.map { it.show.id })
        assertEquals(2, ranked.first().seedCount)
        assertEquals(listOf("Severance", "Dark"), ranked.first().becauseOf)
    }

    @Test
    fun `drops shows already in the library`() {
        // The tab answers "what next", so something already followed is never an answer.
        val ranked =
            rankRecommendations(
                listOf(SeededResults("Severance", listOf(show(1), show(2), show(3)))),
                exclude = setOf(2),
            )

        assertEquals(listOf(1, 3), ranked.map { it.show.id })
    }

    @Test
    fun `counts a seed once even when its own list repeats a show`() {
        // Stitched pages can repeat an entry; a duplicate must not manufacture agreement
        // that only one show actually expressed.
        val ranked =
            rankRecommendations(
                listOf(SeededResults("Severance", listOf(show(1), show(1), show(1)))),
            )

        assertEquals(1, ranked.single().seedCount)
        assertEquals(listOf("Severance"), ranked.single().becauseOf)
    }

    @Test
    fun `breaks a tie on rating damped by vote count, not on the raw rating`() {
        // Both have one seed. The 10.0 has three votes and must not win on that alone.
        val ranked =
            rankRecommendations(
                listOf(
                    SeededResults(
                        "Severance",
                        listOf(
                            show(1, voteAverage = 10.0, voteCount = 3),
                            show(2, voteAverage = 8.4, voteCount = 4000),
                        ),
                    ),
                ),
            )

        assertEquals(listOf(2, 1), ranked.map { it.show.id })
    }

    @Test
    fun `orders equally rated ties by id so the list does not reshuffle between loads`() {
        val ranked =
            rankRecommendations(
                listOf(SeededResults("Severance", listOf(show(9), show(4), show(7)))),
            )

        assertEquals(listOf(4, 7, 9), ranked.map { it.show.id })
    }

    @Test
    fun `honours the limit`() {
        val many = (1..50).map { show(it) }
        assertEquals(5, rankRecommendations(listOf(SeededResults("S", many)), limit = 5).size)
    }

    @Test
    fun `returns nothing for an empty library rather than failing`() {
        assertEquals(emptyList<Candidate>(), rankRecommendations(emptyList()))
    }

    @Test
    fun `damps an unvoted show towards the middle and leaves a well voted one alone`() {
        assertEquals(6.0, weightedRating(9.0, 0), 0.001)
        assertTrue(weightedRating(9.0, 10_000) > 8.9)
    }

    @Test
    fun `names at most two shows in a reason and counts the rest`() {
        assertEquals("Because you follow A", describeReason(listOf("A")))
        assertEquals("Because you follow A and B", describeReason(listOf("A", "B")))
        assertEquals(
            "Because you follow A, B and 1 other",
            describeReason(listOf("A", "B", "C")),
        )
        assertEquals(
            "Because you follow A, B and 2 others",
            describeReason(listOf("A", "B", "C", "D")),
        )
    }
}
