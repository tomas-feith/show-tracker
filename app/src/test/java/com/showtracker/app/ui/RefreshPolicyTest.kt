package com.showtracker.app.ui

import com.showtracker.app.domain.Discovery
import com.showtracker.app.domain.RefreshOutcome
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.network.TmdbException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The rules a refresh applies to its own outcome.
 *
 * These were two private decisions inside `LibraryViewModel` and one copy of them inside
 * `RefreshWorker` that had already drifted: the worker recorded both markers whatever
 * happened. They are pure functions over data, and the ViewModel that held them cannot be
 * constructed off a device, so they are tested here rather than through it.
 */
class RefreshPolicyTest {
    private val now: Instant = Instant.parse("2026-09-09T12:00:00Z")

    private fun show(id: Int) = TrackedShow(id = id, name = "Show $id")

    private fun outcome(
        shows: List<TrackedShow>,
        failures: Map<Int, Throwable> = emptyMap(),
        discoveries: List<Discovery> = emptyList(),
    ) = RefreshOutcome(shows, discoveries, failures)

    @Test
    fun `a refresh where every show failed has not checked anything`() {
        val shows = listOf(show(1), show(2))
        val allFailed =
            outcome(shows, mapOf(1 to TmdbException.Offline(), 2 to TmdbException.Offline()))

        assertFalse(allFailed.reachedTmdb)
    }

    @Test
    fun `one show still answering counts as a check`() {
        val shows = listOf(show(1), show(2))

        assertTrue(outcome(shows, mapOf(1 to TmdbException.Offline())).reachedTmdb)
        assertTrue(outcome(shows).reachedTmdb)
    }

    @Test
    fun `an empty library counts as checked, since there was nothing to ask about`() {
        // Otherwise a library with no shows would never record a timestamp and would look
        // permanently stale, refreshing on every single resume for ever.
        assertTrue(outcome(emptyList()).reachedTmdb)
    }

    @Test
    fun `a transient failure leaves the backfill unfinished`() {
        val shows = listOf(show(1), show(2))

        assertFalse(outcome(shows, mapOf(1 to TmdbException.Offline())).backfillLanded)
        assertFalse(outcome(shows, mapOf(1 to TmdbException.RateLimited())).backfillLanded)
    }

    @Test
    fun `a failure belonging to one show does not`() {
        // TMDB does 404 an id that was merged into another. Waiting for that show to
        // succeed would refetch the whole library on every resume, for ever.
        val shows = listOf(show(1), show(2))
        val gone = outcome(shows, mapOf(1 to TmdbException.Http(404)))

        assertTrue(gone.backfillLanded)
        assertTrue(gone.reachedTmdb)
    }

    @Test
    fun `a library never checked refreshes`() {
        assertTrue(shouldRefresh(null, backfilledVersion = 5, backfillVersion = 5, now = now))
    }

    @Test
    fun `an unparseable timestamp is treated as never checked`() {
        assertTrue(
            shouldRefresh("not a timestamp", backfilledVersion = 5, backfillVersion = 5, now = now),
        )
    }

    @Test
    fun `a library checked within the window does not`() {
        val recent = now.minus(STALE_AFTER).plusSeconds(60).toString()

        assertFalse(shouldRefresh(recent, backfilledVersion = 5, backfillVersion = 5, now = now))
    }

    @Test
    fun `a library checked longer ago than the window does`() {
        val old = now.minus(STALE_AFTER).minusSeconds(60).toString()

        assertTrue(shouldRefresh(old, backfilledVersion = 5, backfillVersion = 5, now = now))
    }

    @Test
    fun `a missed backfill refreshes whatever the clock says`() {
        // The point of the recorded version: a column added by an upgrade appears on the
        // screen the user just updated to see it, not up to six hours later.
        val recent = now.minusSeconds(60).toString()

        assertTrue(shouldRefresh(recent, backfilledVersion = 4, backfillVersion = 5, now = now))
        assertFalse(shouldRefresh(recent, backfilledVersion = 5, backfillVersion = 5, now = now))
    }

    @Test
    fun `the window is the six hours the app documents`() {
        assertEquals(6, STALE_AFTER.toHours())
    }
}
