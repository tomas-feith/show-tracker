package com.showtracker.app.notify

import com.showtracker.app.domain.Discovery
import com.showtracker.app.domain.Season
import com.showtracker.app.domain.TrackedShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording of the one notification this app exists to send. Kept as a pure function so
 * it can be checked here rather than by triggering a background worker and watching a
 * phone - the batching in particular is hard to produce on demand.
 */
class NotificationTextTest {
    private fun discovery(
        id: Int,
        name: String,
        seasonNumber: Int,
    ) = Discovery(
        show = TrackedShow(id = id, name = name),
        season = Season(seasonNumber, "Season $seasonNumber", "2026-08-01", 10),
    )

    @Test
    fun `says nothing when nothing was found`() {
        assertNull(discoveryText(emptyList()))
    }

    @Test
    fun `names the show and the season for a single discovery`() {
        val text = discoveryText(listOf(discovery(1, "Shōgun", 2)))
        assertEquals("Shōgun - new season", text?.title)
        assertEquals("Season 2 is out.", text?.body)
    }

    @Test
    fun `collapses several discoveries into one notification`() {
        // Five separate alerts would be noise rather than news.
        val text =
            discoveryText(
                listOf(
                    discovery(1, "Shōgun", 2),
                    discovery(2, "Glória", 3),
                ),
            )
        assertEquals("2 shows have new seasons", text?.title)
        assertEquals("Shōgun S2, Glória S3", text?.body)
    }

    @Test
    fun `names at most four shows before falling back to and more`() {
        val many = (1..6).map { discovery(it, "Show $it", it) }
        val text = discoveryText(many)

        assertEquals("6 shows have new seasons", text?.title)
        assertTrue(text!!.body.endsWith(", and more"))
        // Four named, and the fifth must not appear.
        assertTrue(text.body.contains("Show 4 S4"))
        assertTrue(!text.body.contains("Show 5"))
    }

    @Test
    fun `does not say and more when exactly four were found`() {
        val four = (1..4).map { discovery(it, "Show $it", it) }
        val text = discoveryText(four)
        assertTrue(!text!!.body.contains("and more"))
        assertEquals("Show 1 S1, Show 2 S2, Show 3 S3, Show 4 S4", text.body)
    }

    @Test
    fun `carries non-ASCII show names into the notification unchanged`() {
        val text = discoveryText(listOf(discovery(1, "Shōgun", 2)))
        assertTrue(text!!.title.any { it.code == 0x14D })
    }
}
