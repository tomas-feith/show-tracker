package com.showtracker.app.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.showtracker.app.AppContainer
import com.showtracker.app.data.ApiKeySource
import com.showtracker.app.data.DiscoverLibrary
import com.showtracker.app.domain.SearchResult
import com.showtracker.app.network.TmdbClient
import com.showtracker.app.ui.catchingUserFacing
import com.showtracker.app.ui.components.Preview
import com.showtracker.app.ui.components.PreviewController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * TMDB's recommendations for the one show being looked at.
 *
 * [loaded] is tracked separately from `items.isEmpty()` for the same reason the discovery
 * tabs do it: an empty list is a real answer for an obscure show, and without the flag that
 * outcome would re-request on every recomposition that reaches [ShowDetailViewModel.load].
 */
data class SimilarShows(
    val items: List<SearchResult> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
)

/**
 * The detail screen's own network state: "more like this", and the preview sheet it opens.
 *
 * Separate from `LibraryViewModel`, which knows nothing about TMDB lookups made on behalf
 * of one screen, and from `DiscoverViewModel`, which holds a ranked pool built from the
 * whole library. This asks TMDB a much narrower question - one show's list, unranked,
 * because with a single seed there is no agreement between seeds to rank by - and it is
 * scoped to the navigation entry, so opening a different show asks about that one.
 *
 * Takes [ApiKeySource] and [DiscoverLibrary] rather than the concrete `Settings` and
 * `LibraryRepository`, so it can be exercised without a `Context`; see `data/Sources.kt`.
 */
class ShowDetailViewModel(
    private val tmdb: TmdbClient,
    private val settings: ApiKeySource,
    private val library: DiscoverLibrary,
) : ViewModel() {
    private val previews = PreviewController(tmdb, settings, viewModelScope)

    private val _similar = MutableStateFlow(SimilarShows())
    val similar: StateFlow<SimilarShows> = _similar.asStateFlow()

    val preview: StateFlow<Preview?> = previews.preview

    /** Which show the list in [similar] belongs to, so a second screen cannot inherit it. */
    private var loadedFor: Int? = null

    /**
     * Fetch the suggestions for [showId], unless they are already loading or loaded.
     *
     * Called from a `LaunchedEffect`, which runs again on a configuration change, so the
     * guard is what keeps a rotation from costing another request.
     */
    fun load(showId: Int) {
        val current = _similar.value
        if (current.loading || (current.loaded && loadedFor == showId)) return

        loadedFor = showId
        viewModelScope.launch {
            _similar.value = SimilarShows(loading = true)
            catchingUserFacing {
                val key = settings.apiKey.first() ?: error("No TMDB key configured.")
                val dismissed = library.dismissedIds()
                tmdb
                    .recommendationsFor(key, showId)
                    // A show cannot be a suggestion for itself, and one the user has
                    // already turned down for good should not reappear here just because
                    // it was reached from a different screen: "not interested" is about
                    // the show, not about where it was seen.
                    .filterNot { it.id == showId || it.id in dismissed }
                    .take(SUGGESTION_COUNT)
            }.onSuccess { results ->
                _similar.value = SimilarShows(items = results, loaded = true)
            }.onFailure { failure ->
                // Deliberately not `loaded`: a failed fetch should be retried when the
                // screen is opened again, rather than remembered as "nothing to suggest".
                _similar.value =
                    SimilarShows(error = failure.message ?: "Could not load suggestions.")
            }
        }
    }

    fun openPreview(result: SearchResult) {
        previews.open(result)
    }

    fun closePreview() {
        previews.close()
    }

    /** Follow succeeded, so drop the row: the section is for shows you do not have. */
    fun onFollowed(id: Int) {
        remove(id)
    }

    /** "Not interested", on the same terms as the discovery screen: hidden for good. */
    fun dismiss(id: Int) {
        val name =
            _similar.value.items
                .firstOrNull { it.id == id }
                ?.name
                ?: previews.preview.value
                    ?.takeIf { it.id == id }
                    ?.name
                ?: ""

        viewModelScope.launch {
            library.dismiss(id, name, Instant.now().toString())
            remove(id)
            if (previews.preview.value?.id == id) closePreview()
        }
    }

    private fun remove(id: Int) {
        _similar.update { it.copy(items = it.items.filterNot { show -> show.id == id }) }
    }

    fun showError(message: String) {
        _similar.update { it.copy(error = message) }
    }

    fun dismissError() {
        _similar.update { it.copy(error = null) }
    }

    companion object {
        /**
         * How many suggestions the section shows.
         *
         * TMDB returns up to twenty, but this is the foot of a screen someone opened to
         * look at one show, not a browsing list: ten is enough to be worth the scroll, and
         * the tail of a single show's list is what the discovery screen exists for.
         */
        const val SUGGESTION_COUNT = 10

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ShowDetailViewModel(
                        container.tmdb,
                        container.settings,
                        container.library,
                    ) as T
            }
    }
}
