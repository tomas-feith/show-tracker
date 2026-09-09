package com.showtracker.app.ui

import com.showtracker.app.domain.RefreshOutcome
import com.showtracker.app.network.TmdbException
import java.time.Duration
import java.time.Instant

// What a refresh is allowed to conclude from its own outcome.
//
// Pure functions with no Android in them, in their own file rather than on
// `LibraryViewModel`, because both callers need them and only one of them is a ViewModel:
// the in-app refresh and `RefreshWorker` were making these decisions separately and had
// already drifted apart - the worker recorded the backfill unconditionally, so a
// background refresh that lost half the library to a rate limit still claimed the new
// columns were filled in. One home for the rules, and one set of tests over them.

/** How long a library stays fresh before opening the app refreshes it. */
val STALE_AFTER: Duration = Duration.ofHours(6)

/**
 * Whether a failure is worth waiting out before calling the backfill done.
 *
 * Only connectivity and rate limiting are: they are about the whole library rather than one
 * show, and the next attempt is likely to succeed. A per-show failure is not - TMDB does
 * 404 an id that was merged into another, and that show will fail every time. Treating
 * those as unfinished pinned the app into refetching the entire library on every foreground
 * resume, for ever, which is the same failure the recorded version was introduced to
 * prevent.
 */
fun Throwable.isTransientFailure(): Boolean =
    this is TmdbException.Offline || this is TmdbException.RateLimited

/**
 * Whether this refresh actually reached TMDB for anything.
 *
 * False only when every single show failed, which is what an offline phone or a rejected
 * key looks like from here - `refreshShows` absorbs per-show failures and keeps the
 * previous data, so nothing throws and the caller cannot otherwise tell that outcome from a
 * clean run.
 *
 * It gates the stored timestamp. Stamping `lastCheckedAt` after a refresh that updated
 * nothing told [shouldRefresh] the library was fresh, so the app then refused to try again
 * for six hours - and there is no manual refresh to override it. Losing signal for the
 * minute the app happened to be opened cost the rest of the day's checks.
 */
val RefreshOutcome.reachedTmdb: Boolean
    get() = shows.isEmpty() || failures.size < shows.size

/**
 * Whether the columns an upgrade added can be recorded as filled in.
 *
 * A show that failed for its own sake is not worth waiting for; one that failed because the
 * network did is, since its columns are still blank and the next attempt will fill them.
 */
val RefreshOutcome.backfillLanded: Boolean
    get() = failures.values.none { it.isTransientFailure() }

/**
 * Whether opening the app should refresh.
 *
 * [lastCheckedAt] is the stored ISO timestamp, unparseable or absent meaning "never". The
 * backfill comparison is deliberately independent of the clock: a column added by an
 * upgrade should appear on the screen the user just updated to see it, not up to six hours
 * later.
 */
fun shouldRefresh(
    lastCheckedAt: String?,
    backfilledVersion: Int,
    backfillVersion: Int,
    now: Instant,
): Boolean {
    val last = lastCheckedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
    val stale = last == null || Duration.between(last, now) > STALE_AFTER
    return stale || backfilledVersion < backfillVersion
}
