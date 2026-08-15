package com.showtracker.app.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.showtracker.app.AppContainer
import com.showtracker.app.data.Settings
import com.showtracker.app.domain.SearchResult
import com.showtracker.app.network.TmdbClient
import com.showtracker.app.ui.catchingUserFacing
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
)

class SearchViewModel(
    private val tmdb: TmdbClient,
    private val settings: Settings,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var pending: Job? = null

    /**
     * Debounced: a search fires only once typing pauses.
     *
     * Without this, "breaking bad" is thirteen requests, twelve of which are already
     * obsolete when they return - and TMDB rate limits.
     */
    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, error = null) }

        pending?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false) }
            return
        }

        pending =
            viewModelScope.launch {
                delay(DEBOUNCE_MS)
                _state.update { it.copy(searching = true) }
                catchingUserFacing {
                    val key = settings.apiKey.first()
                    val results =
                        if (key == null) emptyList() else tmdb.searchShows(key, query)
                    // Guard against an out-of-order result overwriting a newer one: the
                    // query may have moved on while this request was in flight.
                    _state.update { current ->
                        if (current.query == query) {
                            current.copy(results = results, searching = false)
                        } else {
                            current
                        }
                    }
                }.onFailure { failure ->
                    _state.update {
                        it.copy(searching = false, error = failure.message ?: "Search failed.")
                    }
                }
            }
    }

    fun showError(message: String) {
        _state.update { it.copy(error = message) }
    }

    companion object {
        private const val DEBOUNCE_MS = 300L

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SearchViewModel(container.tmdb, container.settings) as T
            }
    }
}
