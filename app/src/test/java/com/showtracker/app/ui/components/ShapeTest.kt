package com.showtracker.app.ui.components

import com.showtracker.app.domain.TrackedShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The episode-count-and-length line on the detail screen.
 *
 * Tested because it shipped reading "${it}m episodes" on every show that had a runtime: a
 * `$` that was escaped rather than interpolated, which compiles, passes every other test in
 * the suite, and is only wrong on screen. Nothing here asserted what the string said.
 */
class ShapeTest {
    private val show = TrackedShow(id = 1, name = "The Handmaid's Tale")

    @Test
    fun `names the count first and distributes the length over it`() {
        val result = describeShape(show.copy(numberOfEpisodes = 66, episodeRunTime = 60))
        assertEquals("66 episodes - 60m each", result)
        // The failure that shipped: a literal dollar-brace rather than the number.
        assertEquals(false, result?.contains("$"))
    }

    @Test
    fun `drops each for a single episode, which has nothing to distribute over`() {
        assertEquals(
            "1 episode - 90m",
            describeShape(show.copy(numberOfEpisodes = 1, episodeRunTime = 90)),
        )
    }

    @Test
    fun `renders either half alone`() {
        // TMDB leaves the runtime empty for many recent shows, and the count at 0 for one
        // it has only just listed. Both halves have to stand on their own.
        assertEquals("66 episodes", describeShape(show.copy(numberOfEpisodes = 66)))
        assertEquals("60m", describeShape(show.copy(episodeRunTime = 60)))
    }

    @Test
    fun `is absent entirely when TMDB gave neither`() {
        // Null, so the row is not drawn at all rather than drawn empty.
        assertNull(describeShape(show))
        assertNull(describeShape(show.copy(numberOfEpisodes = 0, episodeRunTime = null)))
    }
}
