package com.showtracker.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.showtracker.app.AppContainer
import com.showtracker.app.data.ApiKeySource
import com.showtracker.app.data.DiscoverLibrary
import com.showtracker.app.domain.Candidate
import com.showtracker.app.domain.SUGGESTIONS_PER_PAGE
import com.showtracker.app.domain.SUGGESTION_POOL
import com.showtracker.app.domain.SearchResult
import com.showtracker.app.domain.SeededResults
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.domain.rankRecommendations
import com.showtracker.app.network.TmdbClient
import com.showtracker.app.ui.catchingUserFacing
import com.showtracker.app.ui.components.Preview
import com.showtracker.app.ui.components.PreviewController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The three ways in.
 *
 * Declared in the order the tabs are drawn: the row reads the ordinal, so moving one here
 * moves it on screen.
 */
enum class DiscoverTab {
    FOR_YOU,
    FAVOURITES,
    TRENDING,
    ;

    /** Whether this tab is built from library shows, and so has a pool to page through. */
    val seeded: Boolean get() = this != TRENDING
}

/**
 * One tab's contents.
 *
 * [loaded] is tracked separately from `items.isEmpty()` because an empty list is a real
 * answer - a one-show library can genuinely produce nothing - and without the flag that
 * outcome would re-request every time the screen was opened.
 */
data class TabData<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    /** A non-fatal remark, e.g. that some seeds' lists did not load. */
    val note: String? = null,
)

data class DiscoverUiState(
    val tab: DiscoverTab = DiscoverTab.FOR_YOU,
    val forYou: TabData<Candidate> = TabData(),
    /** Ranked from the starred shows alone; see [DiscoverViewModel.seedsFor]. */
    val favourites: TabData<Candidate> = TabData(),
    val trending: TabData<SearchResult> = TabData(),
    /** Whether refreshing will show a further page rather than re-asking TMDB. */
    val moreSuggestions: Boolean = false,
)

/**
 * Everything a tab can say about itself apart from what is in it.
 *
 * Extracted so the tabs' differing item types stop being a problem: `TabData<Candidate>`
 * and `TabData<SearchResult>` cannot be edited through one reference, but the fields that
 * every load actually touches are the same four on all of them. One `when` over the tabs
 * now serves the spinner, the error and the note, instead of one per message.
 */
private data class TabStatus(
    val loading: Boolean,
    val loaded: Boolean,
    val error: String?,
    val note: String?,
)

private fun <T> TabData<T>.status(): TabStatus = TabStatus(loading, loaded, error, note)

private fun <T> TabData<T>.withStatus(status: TabStatus): TabData<T> =
    copy(
        loading = status.loading,
        loaded = status.loaded,
        error = status.error,
        note = status.note,
    )

/**
 * Backs the three discovery tabs.
 *
 * Each tab loads on first view and is then held for the session. These are the two most
 * expensive calls the app makes - the seeded tabs are one request per seed show - and
 * neither list changes fast enough to be worth re-fetching on every visit.
 *
 * "For you" and "Favourites" are the same machinery pointed at different seeds, and they
 * are two tabs rather than one because they answer different questions: the first is "given
 * everything I watch", the second is "given the handful I actually love". Ranking by
 * agreement between seeds means a library of eighty shows drowns the five starred ones -
 * whatever the bulk of the library has in common wins every tie - so the only way to ask
 * the second question is to ask it of the favourites alone.
 */
class DiscoverViewModel(
    private val tmdb: TmdbClient,
    private val settings: ApiKeySource,
    private val library: DiscoverLibrary,
) : ViewModel() {
    private val previews = PreviewController(tmdb, settings, viewModelScope)

    private val _state = MutableStateFlow(DiscoverUiState())
    val state: StateFlow<DiscoverUiState> = _state.asStateFlow()

    private var running: Job? = null

    /**
     * Bumped by every load, so a job can tell whether it is still the current one.
     *
     * Cancellation unwinds asynchronously: by the time an abandoned load reaches its
     * `finally`, the load that replaced it may already be running, and clearing the flag
     * then would hide a live spinner and let a second request start alongside the first.
     */
    private var generation = 0

    /**
     * Every ranked suggestion for one seeded tab, of which one page is on screen.
     *
     * Kept whole so refreshing can show the next page instantly rather than asking TMDB the
     * same question and re-ranking to the same answer. See [refresh].
     */
    private class Pool {
        var candidates: List<Candidate> = emptyList()
        var page: Int = 0
    }

    /**
     * Where the next "For you" load starts in the library.
     *
     * Session state, not stored: it is a cursor into a list that changes as shows are
     * followed and dropped, and carrying yesterday's position into a library that has moved
     * under it would buy nothing. Starting from the newest shows on every cold start is
     * also the right first answer.
     */
    private var seedOffset = 0

    /** One pool per seeded tab. Trending has none: it is a single list with no ranking. */
    private val pools =
        DiscoverTab.entries
            .filter { it.seeded }
            .associateWith { Pool() }

    private fun pool(tab: DiscoverTab): Pool = pools.getValue(tab)

    fun selectTab(tab: DiscoverTab) {
        _state.update { it.copy(tab = tab, moreSuggestions = hasMorePages(tab)) }
        load(tab)
    }

    /** Load [tab] unless it is already loaded, or already loading. */
    fun load(
        tab: DiscoverTab = _state.value.tab,
        force: Boolean = false,
    ) {
        val current = dataFor(tab)
        if (current.loading || (current.loaded && !force)) return

        // One load at a time. Switching tabs mid-flight would otherwise leave two requests
        // racing to write unrelated halves of the state.
        running?.cancel()
        val mine = ++generation
        running =
            viewModelScope.launch {
                setStatus(tab) { it.copy(loading = true, error = null, note = null) }

                // The clear has to be in a `finally`. Cancelling the previous load - which
                // the line above does on every tab switch - unwinds it with a
                // CancellationException, and `catchingUserFacing` rethrows those by design,
                // so `onFailure` never runs for a cancelled load. Without this the abandoned
                // tab keeps `loading = true` for ever, which also makes `load` refuse to
                // start it again: a quick switch away and back left a permanent spinner.
                try {
                    catchingUserFacing {
                        val key = settings.apiKey.first() ?: error("No TMDB key configured.")
                        if (tab.seeded) loadSeeded(key, tab) else loadTrending(key)
                    }.onFailure { failure ->
                        fail(tab, failure.message ?: "Could not load suggestions.")
                    }
                } finally {
                    if (mine == generation) setStatus(tab) { it.copy(loading = false) }
                }
            }
    }

    /**
     * Show the next screenful of an already-ranked pool. No network, no re-ranking.
     *
     * Paging rather than re-rolling which shows are used as seeds. Dropping seeds at random
     * would certainly change the answer, but it changes it by destroying the signal the
     * ranking rests on - agreement between several of the user's shows - so the second
     * screenful would be measurably worse rather than merely different. Walking down a list
     * ranked once answers the request actually being made, "show me something else", keeps
     * the good suggestions in their right order, and costs no network at all.
     *
     * This used to be what the refresh button did once, falling through to a re-fetch when
     * the pool ran out. One button doing both was indistinguishable from a broken one: a
     * new set of thirty shows on every tap looks like a list being re-rolled at random,
     * which is exactly what the ranking is built not to do. The two are separate buttons
     * now, each doing only the thing it is named after. [DiscoverUiState.moreSuggestions]
     * says whether this one has anything left to show.
     */
    fun showMore(tab: DiscoverTab = _state.value.tab) {
        if (!tab.seeded) return
        nextPage(tab)
    }

    /**
     * Ask TMDB again, about a different part of the library, and start from the top.
     *
     * Always back to page 1: whatever the seeds were, the best of what they produced
     * belongs at the top of the list, not wherever the previous window's paging had got to.
     *
     * What changes between two refreshes is the seed window - see [seedWindow]. For a
     * library larger than [MAX_SEEDS] that means a genuinely different set of suggestions
     * each time, cycling back round once the library has been covered. For one at or under
     * the cap, and for "Favourites" at any size, the seeds cannot differ, so refreshing is
     * still a deterministic re-ask that gives the same answer until the library changes.
     */
    fun refresh(tab: DiscoverTab = _state.value.tab) {
        load(tab, force = true)
    }

    /** Advance one page if there is one. Returns false when the pool is spent. */
    private fun nextPage(tab: DiscoverTab): Boolean {
        val pool = pool(tab)
        val start = (pool.page + 1) * SUGGESTIONS_PER_PAGE
        if (pool.candidates.isEmpty() || start >= pool.candidates.size) return false
        pool.page += 1
        showPage(tab)
        return true
    }

    private fun showPage(tab: DiscoverTab) {
        val pool = pool(tab)
        val window =
            pool.candidates
                .drop(pool.page * SUGGESTIONS_PER_PAGE)
                .take(SUGGESTIONS_PER_PAGE)

        _state.update { state ->
            val data =
                candidatesFor(state, tab)
                    .copy(items = window, loading = false, loaded = true)
            withCandidates(state, tab, data)
                // Only the tab being looked at owns this flag; a background pool refilling
                // itself must not relabel the button over another tab's list.
                .let { if (it.tab == tab) it.copy(moreSuggestions = hasMorePages(tab)) else it }
        }
    }

    private fun hasMorePages(tab: DiscoverTab): Boolean {
        if (!tab.seeded) return false
        val pool = pool(tab)
        return (pool.page + 1) * SUGGESTIONS_PER_PAGE < pool.candidates.size
    }

    /**
     * Every show eligible to seed [tab], newest first and uncapped.
     *
     * Newest first because what was added most recently is the best stand-in available for
     * what the user is interested in now, and because the order has to be total: the window
     * below cuts it at a fixed size, so a tie broken by the database's row order would move
     * shows in and out of the seed set at random. `ShowDao` orders by id for the same
     * reason.
     */
    private fun eligibleSeeds(
        tab: DiscoverTab,
        shows: List<TrackedShow>,
    ): List<TrackedShow> =
        shows
            .filter { tab != DiscoverTab.FAVOURITES || it.favourite }
            .sortedByDescending { it.addedAt }

    /**
     * The [MAX_SEEDS] shows this load actually asks TMDB about.
     *
     * "For you" is capped because it is one request per seed and a large library would
     * otherwise open a hundred of them for a list nobody scrolls to the end of. The cap
     * used to mean the same forty shows for ever, so refreshing re-asked TMDB the same
     * question and re-ranked to the same answer - correct, and useless as a way of seeing
     * something else. The window now walks: each load starts where the last one ended and
     * wraps, so a library of eighty is asked about in two halves, alternating.
     *
     * A rotation rather than a random sample of forty, because the ranking is agreement
     * between seeds and a full batch is what gives it something to agree about. Sampling
     * would thin the evidence on every refresh; rotating keeps it at full strength and just
     * points it at a different part of the library. The cost is real and worth naming: a
     * show that six of your library recommend can be absent from the next refresh entirely
     * if those six are in the other window. That is the trade for variety, and it is the
     * reason "the same library gives the same list" now holds only for a library at or
     * under the cap - and for "Favourites", which is never windowed.
     *
     * "Favourites" is uncapped and so never rotates. The star is the user saying which
     * shows that tab is about, so dropping some of them answers a question nobody asked.
     */
    private fun seedWindow(
        tab: DiscoverTab,
        eligible: List<TrackedShow>,
    ): List<TrackedShow> {
        if (tab == DiscoverTab.FAVOURITES || eligible.size <= MAX_SEEDS) return eligible

        val start = seedOffset % eligible.size
        return List(MAX_SEEDS) { eligible[(start + it) % eligible.size] }
    }

    /**
     * Move the window on, so the next refresh asks about different shows.
     *
     * Called only where a ranking was actually adopted. A load that kept the previous pool
     * because a seed would not answer has not shown this window's suggestions to anyone, so
     * the next refresh should try it again rather than skip past it.
     *
     * A library at or under the cap has no second window to move to: every load seeds from
     * all of it, and refreshing there is still the deterministic re-ask it was.
     */
    private fun advanceSeedWindow(
        tab: DiscoverTab,
        eligible: Int,
    ) {
        if (tab == DiscoverTab.FAVOURITES || eligible <= MAX_SEEDS) return
        seedOffset = (seedOffset + MAX_SEEDS) % eligible
    }

    private suspend fun loadSeeded(
        key: String,
        tab: DiscoverTab,
    ) {
        val shows = library.all()
        val tracked = shows.map { it.id }.toSet()
        val dismissed = library.dismissedIds()

        val eligible = eligibleSeeds(tab, shows)
        val seeds = seedWindow(tab, eligible)

        val fetched = retryingFailures(key, seeds.map { it.id })
        // Built by walking the seeds rather than the response map, so the ranking's
        // library-order tiebreak is the user's order and not a hash order.
        val seeded =
            seeds.mapNotNull { seed ->
                fetched[seed.id]?.getOrNull()?.let { SeededResults(seed.name, it) }
            }

        val failures = fetched.values.count { it.isFailure }

        // Every seed failing is a failed load, not a library with nothing to suggest.
        // Falling through would paint "follow a few shows and..." over what is really an
        // offline phone or a rejected key, for someone whose library is full.
        if (seeded.isEmpty() && fetched.isNotEmpty()) {
            throw fetched.values.firstNotNullOf { it.exceptionOrNull() }
        }

        // A seed that is still missing after the retry is not a slightly shorter list, it
        // is a different one: the ranking is agreement between seeds, so dropping one
        // restates the count behind every candidate and reorders the whole pool. Ranking
        // over the partial set would therefore overwrite a good list with a worse one that
        // looks exactly as authoritative, which is the failure this guard exists to stop.
        // Better to keep what is on screen and say why it did not change.
        if (failures > 0 && pool(tab).candidates.isNotEmpty()) {
            setStatus(tab) { it.copy(loaded = true, note = describeKept(failures)) }
            return
        }

        // Followed shows are excluded from both seeded tabs, favourites included: a
        // starred show seeding the list is the reason a suggestion is there, and offering
        // it back as the suggestion would be the tab recommending the library to itself.
        pool(tab).candidates =
            rankRecommendations(
                seeded,
                exclude = tracked + dismissed,
                limit = SUGGESTION_POOL,
            )
        pool(tab).page = 0
        showPage(tab)
        advanceSeedWindow(tab, eligible.size)
        // Nothing was kept back, so this is a first list built from an incomplete set. It
        // is shown - a partial list beats a blank tab - but it says so, because the order
        // is not the one the full library would have produced.
        setStatus(tab) { it.copy(note = describePartial(failures)) }
    }

    /**
     * Ask for every seed, then ask once more for the ones that failed.
     *
     * Nearly every failure here is transient - a 429 from asking for forty lists at once,
     * or a phone that lost the network for a moment - and one retry turns most of them
     * into successes rather than into a caveat the user has to read. The delay is there
     * because retrying a rate limit immediately is how a rate limit is earned again.
     *
     * Whatever the second attempt returns is what counts, failure included: two failures
     * in a row is the honest answer, and the caller is about to act on it.
     */
    private suspend fun retryingFailures(
        key: String,
        ids: List<Int>,
    ): Map<Int, Result<List<SearchResult>>> {
        val first = tmdb.fetchRecommendations(key, ids)
        val failed = first.filterValues { it.isFailure }.keys
        if (failed.isEmpty()) return first

        delay(RETRY_DELAY_MS)
        return first + tmdb.fetchRecommendations(key, failed.toList())
    }

    private suspend fun loadTrending(key: String) {
        val trending = tmdb.trendingShows(key)
        _state.update {
            it.copy(trending = it.trending.copy(items = trending, loading = false, loaded = true))
        }
    }

    // --- preview ---

    val preview: StateFlow<Preview?> = previews.preview

    fun openPreview(result: SearchResult) {
        previews.open(result)
    }

    fun closePreview() {
        previews.close()
    }

    // --- dismissals ---

    /**
     * "Not interested": drop a suggestion and never rank it again.
     *
     * Removed from the pools in place rather than by reloading, so the list does not
     * reshuffle under the user's finger and the page refills from behind instead of leaving
     * a gap. Both seeded pools are swept: the same show can sit in either, and a dismissal
     * is about the show rather than about the tab it was seen on.
     */
    fun dismiss(id: Int) {
        // Read before the removal below, and from the sheet if no pool holds it - dismissing
        // from an already-stale page would otherwise store a blank name and leave an
        // unidentifiable row in the hidden-shows list.
        val name =
            pools.values
                .firstNotNullOfOrNull { pool ->
                    pool.candidates
                        .firstOrNull { it.show.id == id }
                        ?.show
                        ?.name
                }
                ?: previews.preview.value
                    ?.takeIf { it.id == id }
                    ?.name
                ?: ""

        viewModelScope.launch {
            library.dismiss(id, name, Instant.now().toString())
            removeFromPools(id)
            if (previews.preview.value?.id == id) closePreview()
        }
    }

    /** Drop a followed show out of the pools, so the page refills rather than showing a tick. */
    fun onFollowed(id: Int) {
        removeFromPools(id)
    }

    private fun removeFromPools(id: Int) {
        pools.forEach { (tab, pool) ->
            if (pool.candidates.none { it.show.id == id }) return@forEach
            pool.candidates = pool.candidates.filterNot { it.show.id == id }
            // Removing the last item of the last page would otherwise leave it blank.
            if (pool.page > 0 && pool.page * SUGGESTIONS_PER_PAGE >= pool.candidates.size) {
                pool.page -= 1
            }
            showPage(tab)
        }
    }

    fun showError(message: String) {
        setStatus(_state.value.tab) { it.copy(error = message) }
    }

    fun dismissError() {
        setStatus(_state.value.tab) { it.copy(error = null) }
    }

    /**
     * Deliberately leaves `loaded` false: a failed tab should be retried when the user
     * comes back to it, not left as a permanent blank.
     */
    private fun fail(
        tab: DiscoverTab,
        message: String,
    ) {
        setStatus(tab) { it.copy(loading = false, error = message) }
    }

    /** Edit everything about a tab except what is in it; see [TabStatus]. */
    private fun setStatus(
        tab: DiscoverTab,
        block: (TabStatus) -> TabStatus,
    ) {
        _state.update { state ->
            when (tab) {
                DiscoverTab.FOR_YOU -> {
                    state.copy(forYou = state.forYou.withStatus(block(state.forYou.status())))
                }

                DiscoverTab.FAVOURITES -> {
                    state.copy(
                        favourites =
                            state.favourites.withStatus(block(state.favourites.status())),
                    )
                }

                DiscoverTab.TRENDING -> {
                    state.copy(
                        trending = state.trending.withStatus(block(state.trending.status())),
                    )
                }
            }
        }
    }

    private fun dataFor(tab: DiscoverTab): TabData<*> =
        when (tab) {
            DiscoverTab.FOR_YOU -> _state.value.forYou
            DiscoverTab.FAVOURITES -> _state.value.favourites
            DiscoverTab.TRENDING -> _state.value.trending
        }

    companion object {
        /** Ceiling on how many shows seed "For you"; see [seedsFor]. */
        const val MAX_SEEDS = 40

        /** How long to wait before re-asking for the seeds that failed; see [retryingFailures]. */
        const val RETRY_DELAY_MS = 400L

        private fun candidatesFor(
            state: DiscoverUiState,
            tab: DiscoverTab,
        ): TabData<Candidate> =
            when (tab) {
                DiscoverTab.FOR_YOU -> state.forYou

                DiscoverTab.FAVOURITES -> state.favourites

                // Unreachable: trending holds no candidates and has no pool to page.
                DiscoverTab.TRENDING -> TabData()
            }

        private fun withCandidates(
            state: DiscoverUiState,
            tab: DiscoverTab,
            data: TabData<Candidate>,
        ): DiscoverUiState =
            when (tab) {
                DiscoverTab.FOR_YOU -> state.copy(forYou = data)
                DiscoverTab.FAVOURITES -> state.copy(favourites = data)
                DiscoverTab.TRENDING -> state
            }

        /** What the tab says when it showed a list built from an incomplete set of seeds. */
        private fun describePartial(failures: Int): String? =
            when (failures) {
                0 -> null
                1 -> "Incomplete: one show's suggestions could not be loaded."
                else -> "Incomplete: $failures shows' suggestions could not be loaded."
            }

        /** What it says when it declined to replace a good list with a partial one. */
        private fun describeKept(failures: Int): String =
            if (failures == 1) {
                "Kept the previous suggestions: one show's list could not be loaded."
            } else {
                "Kept the previous suggestions: $failures shows' lists could not be loaded."
            }

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DiscoverViewModel(
                        container.tmdb,
                        container.settings,
                        container.library,
                    ) as T
            }
    }
}
