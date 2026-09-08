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
     * What refresh does: show the next page of a seeded tab, or fetch afresh once spent.
     *
     * Paging rather than re-rolling which shows are used as seeds. Dropping seeds at random
     * would certainly change the answer, but it changes it by destroying the signal the
     * ranking rests on - agreement between several of the user's shows - so the second
     * screenful would be measurably worse rather than merely different. Walking down a list
     * ranked once answers the request actually being made, "show me something else", keeps
     * the good suggestions in their right order, and costs no network at all.
     *
     * Trending has nothing to page through, so there it is a plain re-fetch.
     */
    fun refresh(tab: DiscoverTab = _state.value.tab) {
        if (tab.seeded && nextPage(tab)) return
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
     * Which library shows seed [tab].
     *
     * Newest first, then capped. This is one request per seed, and a large library would
     * otherwise open a hundred of them for a list nobody scrolls to the end of. What was
     * added most recently is also the best stand-in available for what the user is
     * interested in now.
     */
    private fun seedsFor(
        tab: DiscoverTab,
        shows: List<TrackedShow>,
    ): List<TrackedShow> =
        shows
            .filter { tab != DiscoverTab.FAVOURITES || it.favourite }
            .sortedByDescending { it.addedAt }
            .take(MAX_SEEDS)

    private suspend fun loadSeeded(
        key: String,
        tab: DiscoverTab,
    ) {
        val shows = library.all()
        val tracked = shows.map { it.id }.toSet()
        val dismissed = library.dismissedIds()

        val seeds = seedsFor(tab, shows)

        val fetched = tmdb.fetchRecommendations(key, seeds.map { it.id })
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
        setStatus(tab) { it.copy(note = describeFailures(failures)) }
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
        /** Ceiling on how many shows seed one tab; see [seedsFor]. */
        const val MAX_SEEDS = 40

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

        private fun describeFailures(failures: Int): String? =
            when (failures) {
                0 -> null
                1 -> "One show's suggestions could not be loaded."
                else -> "$failures shows' suggestions could not be loaded."
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
