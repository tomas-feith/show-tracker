package com.showtracker.app.ui.components

import com.showtracker.app.domain.TrackedShow
import org.junit.Assert.assertEquals
import org.junit.Test

/** The detail screen's header line: year, kind, status. */
class KindTest {
    private val show =
        TrackedShow(
            id = 1,
            name = "Chernobyl",
            firstAirDate = "2019-05-06",
            status = "Ended",
        )

    @Test
    fun `names a kind that distinguishes the show`() {
        assertEquals(
            "2019 - Miniseries - Ended",
            describeKind(show.copy(type = "Miniseries")),
        )
        assertEquals(
            "2019 - Documentary - Ended",
            describeKind(show.copy(type = "Documentary")),
        )
    }

    @Test
    fun `stays quiet about a kind TMDB gives nearly everything`() {
        // "Scripted" is the default for drama and comedy alike: a word on every row that
        // separates none of them.
        assertEquals("2019 - Ended", describeKind(show.copy(type = "Scripted")))
        assertEquals("2019 - Ended", describeKind(show.copy(type = "Video")))
        assertEquals("2019 - Ended", describeKind(show))
    }

    @Test
    fun `drops a part TMDB has not got rather than leaving a gap`() {
        // A show announced but not scheduled has no first air date at all.
        assertEquals(
            "Miniseries - Returning Series",
            describeKind(
                show.copy(firstAirDate = null, type = "Miniseries", status = "Returning Series"),
            ),
        )
        assertEquals("2019", describeKind(show.copy(status = "")))
    }

    @Test
    fun `separates a miniseries still airing from an ongoing drama`() {
        // The case the type exists for: status alone calls both of these the same thing.
        val mini = show.copy(type = "Miniseries", status = "Returning Series")
        val drama = show.copy(type = "Scripted", status = "Returning Series")

        assertEquals("2019 - Miniseries - Returning Series", describeKind(mini))
        assertEquals("2019 - Returning Series", describeKind(drama))
    }
}
