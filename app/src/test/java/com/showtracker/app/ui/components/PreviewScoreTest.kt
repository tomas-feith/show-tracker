package com.showtracker.app.ui.components

import com.showtracker.app.domain.ShowDetail
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which score the preview sheet shows, and when.
 *
 * The sheet opens on a search hit and fills in from a detail fetch a moment later, so there
 * are two sources for the same number and a window where only one exists.
 */
class PreviewScoreTest {
    private val hit =
        Preview(
            id = 1396,
            name = "Shōgun",
            posterPath = null,
            firstAirDate = "2024-02-27",
            voteAverage = 8.4,
            voteCount = 4321,
        )

    private fun detail(
        voteAverage: Double,
        voteCount: Int,
    ) = ShowDetail(
        id = 1396,
        name = "Shōgun",
        overview = "",
        posterPath = null,
        firstAirDate = "2024-02-27",
        status = "Ended",
        seasons = emptyList(),
        lastEpisode = null,
        nextEpisode = null,
        voteAverage = voteAverage,
        voteCount = voteCount,
    )

    @Test
    fun `shows the hit's score before the detail fetch returns`() {
        // The row the user tapped already showed this. A score that vanished on opening the
        // sheet and reappeared a moment later would read as the app changing its mind.
        assertEquals(8.4 to 4321, hit.score)
    }

    @Test
    fun `prefers the detail figures once they arrive`() {
        val loaded = hit.copy(loading = false, detail = detail(8.5, 4400))
        assertEquals(8.5 to 4400, loaded.score)
    }

    @Test
    fun `keeps the hit's score when the detail carries none`() {
        // Falling through to the detail's 0 would blank a score that was on screen a moment
        // earlier, which is worse than showing the slightly older of two identical numbers.
        val loaded = hit.copy(loading = false, detail = detail(0.0, 0))
        assertEquals(8.4 to 4321, loaded.score)
    }

    @Test
    fun `has no score to show when neither source has one`() {
        val unrated = hit.copy(voteAverage = 0.0, voteCount = 0)
        // ScoreTag draws nothing on a zero count, so this is "no score", not "rated 0.0".
        assertEquals(0.0 to 0, unrated.score)
    }
}
