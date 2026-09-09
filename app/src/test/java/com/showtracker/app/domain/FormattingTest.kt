package com.showtracker.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How day counts and episode markers are worded.
 *
 * Split out of `NewnessTest`, which had grown past what one class should hold. These are
 * the pure formatting rules - no dates, no library, no state - and the reason they have
 * tests at all is that the JavaScript they replace rounded where Kotlin truncates.
 */
class FormattingTest {
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
    fun `counts years in both directions`() {
        // Anything past a year used to flatten to "over a year away" however far off it
        // was, while the same distance in the past said how many years. A season announced
        // for three years' time is rare, and it should read the way the past does.
        assertEquals("over a year away", describeDays(400, Direction.UNTIL))
        assertEquals("in 3 years", describeDays(3 * 365 + 4, Direction.UNTIL))
        assertEquals("3 years ago", describeDays(3 * 365 + 4, Direction.AGO))
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
