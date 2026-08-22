package com.showtracker.app.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.showtracker.app.AppContainer
import com.showtracker.app.data.LibraryRepository
import com.showtracker.app.data.Settings
import com.showtracker.app.domain.Candidate
import com.showtracker.app.domain.SUGGESTIONS_PER_PAGE
import com.showtracker.app.domain.SUGGESTION_POOL
import com.showtracker.app.domain.SearchResult
import com.showtracker.app.domain.SeededResults
import com.showtracker.app.domain.ShowDetail
import com.showtracker.app.domain.rankRecommendations
import com.showtracker.app.network.TmdbClient
import com.showtracker.app.ui.catchingUserFacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

enum class DiscoverTab {
    FOR_YOU,
    TRENDING,
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

/**
 * The show being previewed before the user commits to following it.
 *
 * Held here rather than in the screen because what fills it is a network fetch, and one
 * that survives a rotation is worth the few lines of state it costs.
 */
data class Preview(
    val id: Int,
    val name: String,
    val posterPath: String?,
    val firstAirDate: String?,
    val loading: Boolean = true,
    val detail: ShowDetail? = null,
    val error: String? = null,
)

data class DiscoverUiState(
    val tab: DiscoverTab = DiscoverTab.FOR_YOU,
    val forYou: TabData<Candidate> = TabData(),
    val trending: TabData<SearchResult> = TabData(),
    val preview: Preview? = null,
    /** Whether refreshing will show a further page rather than re-asking TMDB. */
    val moreSuggestions: Boolean = false,
)

/**
 * Backs both discovery tabs.
 *
 * Each tab loads on first view and is then held for the session. These are the two most
 * expensive calls the app makes - "For you" is one request per seed show - and neither
 * list changes fast enough to be worth re-fetching on every visit.
 */
class DiscoverViewModel(
    private val tmdb: TmdbClient,
    private val settings: Settings,
    private val library: LibraryRepository,
) : ViewModel() {
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
     * Every ranked suggestion, of which one page is on screen.
     *
     * Kept whole so refreshing can show the next page instantly rather than asking TMDB the
     * same question and re-ranking to the same answer. See [refreshForYou].
     */
    private var pool: List<Candidate> = emptyList()
    private var page = 0

    fun selectTab(tab: DiscoverTab) {
        _state.update { it.copy(tab = tab) }
        load(tab)
    }

    /** Load [tab] unless it is already loaded, or already loading. */
    fun load(
        tab: DiscoverTab = _state.value.tab,
        force: Boolean = false,
    ) {
        val current =
            when (tab) {
                DiscoverTab.FOR_YOU -> _state.value.forYou
                DiscoverTab.TRENDING -> _state.value.trending
            }
        if (current.loading || (current.loaded && !force)) return

        // One load at a time. Switching tabs mid-flight would otherwise leave two requests
        // racing to write unrelated halves of the state.
        running?.cancel()
        val mine = ++generation
        running =
            viewModelScope.launch {
                startLoading(tab)

                // The clear has to be in a `finally`. Cancelling the previous load - which
                // the line above does on every tab switch - unwinds it with a
                // CancellationException, and `catchingUserFacing` rethrows those by design,
                // so `onFailure` never runs for a cancelled load. Without this the abandoned
                // tab keeps `loading = true` for ever, which also makes `load` refuse to
                // start it again: a quick switch away and back left a permanent spinner.
                try {
                    catchingUserFacing {
                        val key = settings.apiKey.first() ?: error("No TMDB key configured.")
                        when (tab) {
                            DiscoverTab.FOR_YOU -> loadForYou(key)
                            DiscoverTab.TRENDING -> loadTrending(key)
                        }
                    }.onFailure { failure ->
                        fail(tab, failure.message ?: "Could not load suggestions.")
                    }
                } finally {
                    if (mine == generation) stopLoading(tab)
                }
            }
    }

    /**
     * What refresh does on "For you": show the next page, or fetch afresh once spent.
     *
     * Paging rather than re-rolling which shows are used as seeds. Dropping seeds at random
     * would certainly change the answer, but it changes it by destroying the signal the
     * ranking rests on - agreement between several of the user's shows - so the second
     * screenful would be measurably worse rather than merely different. Walking down a list
     * ranked once answers the request actually being made, "show me something else", keeps
     * the good suggestions in their right order, and costs no network at all.
     */
    fun refreshForYou() {
        if (nextPage()) return
        load(DiscoverTab.FOR_YOU, force = true)
    }

    /** Advance one page if there is one. Returns false when the pool is spent. */
    private fun nextPage(): Boolean {
        val start = (page + 1) * SUGGESTIONS_PER_PAGE
        if (pool.isEmpty() || start >= pool.size) return false
        page += 1
        showPage()
        return true
    }

    private fun showPage() {
        val window = pool.drop(page * SUGGESTIONS_PER_PAGE).take(SUGGESTIONS_PER_PAGE)
        _state.update {
            it.copy(
                forYou = it.forYou.copy(items = window, loading = false, loaded = true),
                moreSuggestions = (page + 1) * SUGGESTIONS_PER_PAGE < pool.size,
            )
        }
    }

    private suspend fun loadForYou(key: String) {
        val shows = library.all()
        val tracked = shows.map { it.id }.toSet()
        val dismissed = library.dismissedIds()

        // Newest first, then capped. This is one request per seed, and a large library
        // would otherwise open a hundred of them for a list nobody scrolls to the end of.
        // What was added most recently is also the best stand-in available for what the
        // user is interested in now.
        val seeds = shows.sortedByDescending { it.addedAt }.take(MAX_SEEDS)

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

        pool =
            rankRecommendations(
                seeded,
                exclude = tracked + dismissed,
                limit = SUGGESTION_POOL,
            )
        page = 0
        showPage()
        _state.update { it.copy(forYou = it.forYou.copy(note = describeFailures(failures))) }
    }

    private suspend fun loadTrending(key: String) {
        val trending = tmdb.trendingShows(key)
        _state.update {
            it.copy(trending = it.trending.copy(items = trending, loading = false, loaded = true))
        }
    }

    // --- preview ---

    /** Open the sheet for [result], and fetch what it needs to say something useful. */
    fun openPreview(result: SearchResult) {
        _state.update {
            it.copy(
                preview =
                    Preview(
                        id = result.id,
                        name = result.name,
                        posterPath = result.posterPath,
                        firstAirDate = result.firstAirDate,
                    ),
            )
        }
        fetchPreview(result.id)
    }

    private fun fetchPreview(id: Int) {
        viewModelScope.launch {
            catchingUserFacing {
                val key = settings.apiKey.first() ?: error("No TMDB key configured.")
                tmdb.fetchShow(key, id)
            }.onSuccess { detail ->
                updatePreview(id) { it.copy(loading = false, detail = detail) }
            }.onFailure { failure ->
                updatePreview(id) {
                    it.copy(
                        loading = false,
                        error = failure.message ?: "Could not load that show.",
                    )
                }
            }
        }
    }

    /**
     * Apply [block] only while [id] is still the show on screen.
     *
     * A slow fetch landing after the user closed the sheet, or opened a different show,
     * would otherwise fill it with the wrong synopsis and episode counts.
     */
    private fun updatePreview(
        id: Int,
        block: (Preview) -> Preview,
    ) {
        _state.update { state ->
            val preview = state.preview
            if (preview?.id != id) state else state.copy(preview = block(preview))
        }
    }

    fun closePreview() {
        _state.update { it.copy(preview = null) }
    }

    // --- dismissals ---

    /**
     * "Not interested": drop a suggestion and never rank it again.
     *
     * Removed from the pool in place rather than by reloading, so the list does not
     * reshuffle under the user's finger and the page refills from behind instead of leaving
     * a gap.
     */
    fun dismiss(id: Int) {
        viewModelScope.launch {
            library.dismiss(id, Instant.now().toString())
            removeFromPool(id)
            if (_state.value.preview?.id == id) closePreview()
        }
    }

    /** Drop a followed show out of the pool, so the page refills rather than showing a tick. */
    fun onFollowed(id: Int) {
        removeFromPool(id)
    }

    private fun removeFromPool(id: Int) {
        pool = pool.filterNot { it.show.id == id }
        // Removing the last item of the last page would otherwise leave it blank.
        if (page > 0 && page * SUGGESTIONS_PER_PAGE >= pool.size) page -= 1
        showPage()
    }

    fun showError(message: String) {
        setError(_state.value.tab, message)
    }

    fun dismissError() {
        setError(_state.value.tab, null)
    }

    // The two tabs hold different item types, so `TabData.copy` cannot be called through a
    // shared reference to either. These spell the branch out instead, which is shorter than
    // the generics that would be needed to avoid it.

    private fun startLoading(tab: DiscoverTab) {
        _state.update {
            when (tab) {
                DiscoverTab.FOR_YOU -> {
                    it.copy(forYou = it.forYou.copy(loading = true, error = null, note = null))
                }

                DiscoverTab.TRENDING -> {
                    it.copy(trending = it.trending.copy(loading = true, error = null, note = null))
                }
            }
        }
    }

    private fun stopLoading(tab: DiscoverTab) {
        _state.update {
            when (tab) {
                DiscoverTab.FOR_YOU -> it.copy(forYou = it.forYou.copy(loading = false))
                DiscoverTab.TRENDING -> it.copy(trending = it.trending.copy(loading = false))
            }
        }
    }

    /**
     * Deliberately leaves `loaded` false: a failed tab should be retried when the user
     * comes back to it, not left as a permanent blank.
     */
    private fun fail(
        tab: DiscoverTab,
        message: String,
    ) {
        _state.update {
            when (tab) {
                DiscoverTab.FOR_YOU -> {
                    it.copy(forYou = it.forYou.copy(loading = false, error = message))
                }

                DiscoverTab.TRENDING -> {
                    it.copy(trending = it.trending.copy(loading = false, error = message))
                }
            }
        }
    }

    private fun setError(
        tab: DiscoverTab,
        message: String?,
    ) {
        _state.update {
            when (tab) {
                DiscoverTab.FOR_YOU -> it.copy(forYou = it.forYou.copy(error = message))
                DiscoverTab.TRENDING -> it.copy(trending = it.trending.copy(error = message))
            }
        }
    }

    companion object {
        /** Ceiling on how many library shows are used as seeds; see `loadForYou`. */
        const val MAX_SEEDS = 40

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
