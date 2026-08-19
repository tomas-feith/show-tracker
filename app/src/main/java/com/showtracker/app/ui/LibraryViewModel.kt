package com.showtracker.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.showtracker.app.AppContainer
import com.showtracker.app.data.ImportResult
import com.showtracker.app.data.LibraryRepository
import com.showtracker.app.data.Settings
import com.showtracker.app.data.buildExport
import com.showtracker.app.data.parseExport
import com.showtracker.app.domain.ShowFetcher
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.domain.initialWatermark
import com.showtracker.app.domain.latestAiredSeason
import com.showtracker.app.domain.refreshShows
import com.showtracker.app.network.TmdbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Everything the library screen needs, in one snapshot. */
data class LibraryUiState(
    val ready: Boolean = false,
    val apiKey: String? = null,
    val shows: List<TrackedShow> = emptyList(),
    val lastCheckedAt: String? = null,
)

class LibraryViewModel(
    private val library: LibraryRepository,
    private val settings: Settings,
    private val tmdb: TmdbClient,
) : ViewModel() {
    private val fetcher = ShowFetcher { ids -> tmdb.fetchShows(requireKey(), ids) }

    private var cachedKey: String? = null

    val state: StateFlow<LibraryUiState> =
        combine(
            library.observeLibrary(),
            settings.apiKey,
            settings.lastCheckedAt,
        ) { shows, key, checked ->
            cachedKey = key
            LibraryUiState(ready = true, apiKey = key, shows = shows, lastCheckedAt = checked)
        }.stateIn(
            scope = viewModelScope,
            // Keeps the library warm across a configuration change without holding the
            // database subscription open for a screen nobody is looking at.
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LibraryUiState(),
        )

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun dismissError() {
        _error.value = null
    }

    private fun requireKey(): String = cachedKey ?: error("No TMDB key configured.")

    /**
     * Refresh if the last check is more than six hours old.
     *
     * Called when the library screen appears or the app returns from the background. The
     * periodic worker is best-effort - Android decides whether it runs at all - so this is
     * what actually keeps the library current for someone who opens the app.
     */
    fun refreshIfStale(now: Instant = Instant.now()) {
        viewModelScope.launch {
            // Wait for the first real emission rather than reading `state.value`, which on a
            // cold start is still the placeholder: not ready, no key, no timestamp. Reading
            // it there looked stale (null timestamp) and then did nothing at all, because
            // the key had not loaded either - so the on-open refresh silently never ran
            // until the app had been backgrounded and resumed once.
            val ready = state.first { it.ready }
            if (ready.apiKey == null) return@launch

            val last =
                ready.lastCheckedAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (last == null || Duration.between(last, now) > STALE_AFTER) refresh(now)
        }
    }

    fun refresh(
        now: Instant = Instant.now(),
        today: LocalDate = LocalDate.now(),
    ) {
        val key = cachedKey
        if (key == null || _refreshing.value) return

        viewModelScope.launch {
            _refreshing.value = true
            _error.value = null
            try {
                catchingUserFacing {
                    val outcome = refreshShows(fetcher, library.all(), now, today)
                    library.saveAll(outcome.shows)
                    settings.setLastCheckedAt(now.toString())

                    if (outcome.failures.isNotEmpty()) {
                        val first = outcome.failures.values.first()
                        _error.value =
                            if (outcome.failures.size == 1) {
                                first.message
                            } else {
                                "${outcome.failures.size} shows failed to update. " +
                                    "${first.message}"
                            }
                    }
                }.onFailure { _error.value = it.message ?: "Refresh failed." }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Follow a show.
     *
     * Both watermarks start level with the latest aired season, so following a long-running
     * show does not immediately announce a backlog the user never asked about.
     */
    fun addShow(
        id: Int,
        onError: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            // Guard here as well as in the UI: the fetch below is slow enough that a second
            // tap can arrive before the first has finished.
            if (library.isTracked(id)) return@launch

            catchingUserFacing {
                val detail = tmdb.fetchShow(requireKey(), id)
                val today = LocalDate.now()
                val now = Instant.now().toString()
                val watermark = initialWatermark(detail.seasons, today)

                library.save(
                    TrackedShow(
                        id = detail.id,
                        name = detail.name,
                        posterPath = detail.posterPath,
                        firstAirDate = detail.firstAirDate,
                        status = detail.status,
                        seasons = detail.seasons,
                        lastEpisode = detail.lastEpisode,
                        nextEpisode = detail.nextEpisode,
                        watchedThroughSeason = watermark,
                        knownAiredSeason = watermark,
                        addedAt = now,
                        lastCheckedAt = now,
                    ),
                )
            }.onFailure { onError(it.message ?: "Could not add that show.") }
        }
    }

    fun removeShow(id: Int) {
        viewModelScope.launch { library.remove(id) }
    }

    fun setWatchedThrough(
        id: Int,
        season: Int,
    ) {
        viewModelScope.launch { library.setWatchedThrough(id, season) }
    }

    /**
     * Mark the season the user is partway through, or clear it with null.
     *
     * Deliberately does not touch the watched-through watermark. Starting season 4 is not a
     * claim to have finished season 3 - someone can skip ahead, or start a show in the
     * middle - and inferring one from the other would silently rewrite progress the user
     * never stated.
     */
    fun setInProgress(
        id: Int,
        season: Int?,
    ) {
        viewModelScope.launch { library.setInProgress(id, season) }
    }

    /** "I am up to date": watched through the latest aired season. */
    fun markCaughtUp(id: Int) {
        viewModelScope.launch {
            val show = library.get(id) ?: return@launch
            val latest = latestAiredSeason(show.seasons, LocalDate.now()) ?: return@launch
            library.setWatchedThrough(id, latest.seasonNumber)
        }
    }

    /** The current library as a transfer file. */
    suspend fun exportJson(): String =
        buildExport(library.all(), state.value.lastCheckedAt, Instant.now())

    /**
     * Replace the library from an export file.
     *
     * Replace rather than merge: an import is a restore, and merging would have to invent
     * an answer for a show present in both with different watermarks. The file is the
     * user's own most recent state, so it wins outright - which is also why the UI confirms
     * before calling this.
     */
    suspend fun importJson(text: String): ImportResult {
        val result = parseExport(text)
        if (result is ImportResult.Success) {
            library.replaceWith(result.shows)
            result.lastCheckedAt?.let { settings.setLastCheckedAt(it) }
        }
        return result
    }

    suspend fun saveApiKey(key: String) {
        tmdb.verifyKey(key.trim())
        settings.setApiKey(key)
    }

    suspend fun forgetApiKey() {
        settings.clearApiKey()
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private val STALE_AFTER: Duration = Duration.ofHours(6)

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(
                        container.library,
                        container.settings,
                        container.tmdb,
                    ) as T
            }
    }
}
