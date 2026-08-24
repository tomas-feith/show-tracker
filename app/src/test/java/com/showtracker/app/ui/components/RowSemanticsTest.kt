package com.showtracker.app.ui.components

import com.showtracker.app.domain.Season
import com.showtracker.app.domain.TrackedShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * What a screen reader is told about a library row.
 *
 * `RowSemantics` clears the children's semantics and replaces them with this one string, so
 * anything missing from it is missing from the app for anyone using TalkBack - however
 * plainly it is drawn. These tests exist because the score and episode figures were added
 * to the row and not to this, which made them sighted-only for two commits.
 */
class RowSemanticsTest {
    private val today: LocalDate = LocalDate.parse("2026-08-14")

    private val show =
        TrackedShow(
            id = 1,
            name = "Shōgun",
            status = "Ended",
            seasons = listOf(Season(1, "Season 1", "2024-02-27", 10)),
            watchedThroughSeason = 1,
            voteAverage = 8.44,
            voteCount = 4321,
            episodeRunTime = 60,
            numberOfEpisodes = 10,
        )

    @Test
    fun `says everything the row draws`() {
        val spoken = describeRow(show, today)

        assertTrue("the name is missing: $spoken", spoken.startsWith("Shōgun."))
        assertTrue("the state is missing: $spoken", spoken.contains("Ended"))
        assertTrue("progress is missing: $spoken", spoken.contains("Watched through season 1"))
        assertTrue("the score is missing: $spoken", spoken.contains("Rated 8.4 out of 10"))
        assertTrue("the episode count is missing: $spoken", spoken.contains("10 episodes"))
        assertTrue("the length is missing: $spoken", spoken.contains("60 minutes each"))
    }

    @Test
    fun `speaks the figures rather than the abbreviations on screen`() {
        val spoken = describeRow(show, today)

        // "60m each" is read out as "sixty em each", and a bare "8.4" does not say what it
        // is out of. The visible label stays short; this one is a sentence.
        assertTrue(spoken.contains("60 minutes"))
        assertTrue("abbreviated runtime leaked into speech: $spoken", !spoken.contains("60m"))
    }

    @Test
    fun `leaves out what the row itself leaves out`() {
        // A show TMDB has told us nothing about draws no meta line, and must not be
        // announced as having one.
        val bare = show.copy(voteCount = 0, episodeRunTime = null, numberOfEpisodes = 0)
        assertNull(describeMetaAloud(bare))

        val spoken = describeRow(bare.copy(watchedThroughSeason = 0), today)
        assertTrue("empty clauses leaked in: $spoken", !spoken.contains(". ."))
        assertTrue(!spoken.contains("Rated"))
        assertTrue(!spoken.contains("Watched through"))
    }

    @Test
    fun `drops each when there is one episode to distribute over`() {
        val single = show.copy(numberOfEpisodes = 1, episodeRunTime = 90)
        assertEquals(
            "Rated 8.4 out of 10, 1 episode, 90 minutes",
            describeMetaAloud(single),
        )
    }

    @Test
    fun `ends as one sentence rather than a run of labels`() {
        val spoken = describeRow(show, today)
        assertTrue("should end in a full stop: $spoken", spoken.endsWith("."))
        assertTrue("should not double up separators: $spoken", !spoken.contains(".."))
    }
}
