package com.showtracker.app.ui

import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.showtracker.app.AppContainer
import com.showtracker.app.data.BackupFolder
import com.showtracker.app.data.ImportResult
import com.showtracker.app.data.LibraryRepository
import com.showtracker.app.data.Settings
import com.showtracker.app.data.backupFileName
import com.showtracker.app.data.buildExport
import com.showtracker.app.data.parseExport
import com.showtracker.app.domain.LibraryFilters
import com.showtracker.app.domain.LibrarySort
import com.showtracker.app.domain.ShowFetcher
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.domain.initialWatchedThrough
import com.showtracker.app.domain.initialWatermark
import com.showtracker.app.domain.latestAiredSeason
import com.showtracker.app.domain.refreshShows
import com.showtracker.app.domain.withDetail
import com.showtracker.app.network.TmdbClient
import com.showtracker.app.notify.BackupSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Everything the library screen needs, in one snapshot. */
data class LibraryUiState(
    val ready: Boolean = false,
    val apiKey: String? = null,
    val shows: List<TrackedShow> = emptyList(),
    val lastCheckedAt: String? = null,
    val sort: LibrarySort = LibrarySort.ATTENTION,
)

/**
 * This view model backs four screens - the library, a show, the settings data section and
 * backups - so it is over detekt's function count. Splitting it is a real job and a
 * separate one: the screens share `state`, and pulling any of them out means deciding what
 * owns the library flow first. Suppressed here rather than raised in `detekt.yml`, so the
 * exception stays attached to the file that earned it.
 */
@Suppress("TooManyFunctions")
class LibraryViewModel(
    private val library: LibraryRepository,
    private val settings: Settings,
    private val tmdb: TmdbClient,
    private val backups: BackupFolder,
    private val backupSchedule: BackupSchedule,
) : ViewModel() {
    private val fetcher = ShowFetcher { ids -> tmdb.fetchShows(requireKey(), ids) }

    private var cachedKey: String? = null

    val state: StateFlow<LibraryUiState> =
        combine(
            library.observeLibrary(),
            settings.apiKey,
            settings.lastCheckedAt,
            settings.librarySort,
        ) { shows, key, checked, sort ->
            cachedKey = key
            LibraryUiState(
                ready = true,
                apiKey = key,
                shows = shows,
                lastCheckedAt = checked,
                sort = sort,
            )
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

    fun setSort(sort: LibrarySort) {
        viewModelScope.launch { settings.setLibrarySort(sort) }
    }

    /**
     * What is currently being hidden from the list.
     *
     * Held here rather than in `Settings`, unlike the sort order, and deliberately not
     * persisted: an order is how someone wants their library to look, but a filter is a
     * question asked for one evening - "what is short and good tonight" - and a library
     * that came back three days later still hiding four fifths of itself would read as
     * shows having gone missing. Living on the view model rather than in the screen keeps
     * it across opening a show and coming back, which is the same trip the filter was set
     * to make.
     */
    private val _filters = MutableStateFlow(LibraryFilters())
    val filters: StateFlow<LibraryFilters> = _filters.asStateFlow()

    /** Clearing is [LibraryFilters] with nothing set, so there is no separate reset. */
    fun setFilters(filters: LibraryFilters) {
        _filters.value = filters
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

            val shouldRefresh =
                shouldRefresh(
                    lastCheckedAt = ready.lastCheckedAt,
                    backfilledVersion = settings.backfilledVersion.first(),
                    backfillVersion = BACKFILL_VERSION,
                    now = now,
                )

            if (shouldRefresh) refresh(now)
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
                    library.saveRefreshed(outcome.shows)
                    // Only once something came back. A refresh where every show failed has
                    // checked nothing, and recording it as a check would make the app
                    // refuse to try again until the staleness window had passed - see
                    // RefreshOutcome.reachedTmdb.
                    if (outcome.reachedTmdb) settings.setLastCheckedAt(now.toString())
                    if (outcome.backfillLanded) settings.setBackfilledVersion(BACKFILL_VERSION)

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
     * [knownAiredSeason] always starts level with the latest aired season, whatever the
     * user says they have watched: it records what the app has already told them about, and
     * starting it lower would announce seasons that were out before they ever followed the
     * show. The watched-through watermark is a separate question, and one only the user can
     * answer - see [watchedThrough].
     */
    fun addShow(
        id: Int,
        /**
         * How far the user says they have already watched, or null to let the app guess.
         *
         * The guess - caught up, bar a season still airing - is right for the common case
         * of following something you have just finished, and wrong for every other one:
         * a show you have never seen arrived marked as entirely watched. The preview sheet
         * asks, and passes the answer here.
         */
        watchedThrough: Int? = null,
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
                // The two watermarks part company for a show added mid-season: the airing
                // season has been announced, but it cannot have been watched yet.
                val announced = initialWatermark(detail.seasons, today)
                val watched =
                    watchedThrough
                        ?: initialWatchedThrough(
                            detail.seasons,
                            detail.lastEpisode,
                            detail.nextEpisode,
                            today,
                        )

                library.save(
                    // Through withDetail rather than field by field, so a column added to
                    // ShowDetail cannot reach the refresh and miss this path.
                    TrackedShow(id = detail.id, name = detail.name)
                        .withDetail(detail)
                        .copy(
                            watchedThroughSeason = watched,
                            knownAiredSeason = announced,
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

    /**
     * Star or unstar a show.
     *
     * Takes the new value rather than toggling what is stored: the caller is a button
     * drawn from the library flow, so it already knows which way it is pointing, and a
     * blind toggle would flip twice on a double tap and land back where it started.
     */
    fun setFavourite(
        id: Int,
        favourite: Boolean,
    ) {
        viewModelScope.launch { library.setFavourite(id, favourite) }
    }

    /** "I am up to date": watched through the latest aired season. */
    fun markCaughtUp(id: Int) {
        viewModelScope.launch {
            val show = library.get(id) ?: return@launch
            val latest = latestAiredSeason(show.seasons, LocalDate.now()) ?: return@launch
            library.setWatchedThrough(id, latest.seasonNumber)
        }
    }

    /** The backup folder's own name, for the settings screen. Null if it cannot be read. */
    suspend fun backupFolderName(uri: String): String? = backups.displayName(uri.toUri())

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

    // --- hidden suggestions ---

    val dismissedShows = library.observeDismissedShows()

    /**
     * Un-hide one show.
     *
     * The suggestion does not come back immediately: "For you" holds its ranked pool for
     * the session, so this takes effect on the next full refresh. Saying so on the button
     * would be noise - the user's intent is "stop hiding this", and that is exactly what
     * has happened.
     */
    fun restoreDismissed(id: Int) {
        viewModelScope.launch { library.undismiss(id) }
    }

    fun restoreAllDismissed() {
        viewModelScope.launch { library.clearDismissed() }
    }

    // --- scheduled backups ---

    val backupFolder = settings.backupFolder
    val lastBackupAt = settings.lastBackupAt
    val lastBackupError = settings.lastBackupError

    /**
     * Point scheduled backups at [uri] and restart the schedule.
     *
     * Cancel then schedule, rather than letting the periodic request be replaced in place:
     * the user has just chosen a folder and expects the next run to be measured from now,
     * not to inherit whatever was left of the previous folder's day.
     */
    suspend fun setBackupFolder(uri: String) {
        settings.setBackupFolder(uri)
        backupSchedule.restart()
    }

    suspend fun clearBackupFolder() {
        settings.clearBackupFolder()
        backupSchedule.stop()
    }

    /**
     * Write one backup immediately.
     *
     * Reports through callbacks rather than a state flow because this section is the only
     * caller and it already owns the message it shows; threading another field through the
     * shared library state would put a transient settings message in every screen's
     * snapshot.
     */
    fun backUpNow(
        onDone: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        viewModelScope.launch {
            catchingUserFacing {
                val folder =
                    settings.currentBackupFolder()
                        ?: error("No backup folder chosen yet.")
                val shows = library.all()
                if (shows.isEmpty()) error("There is nothing to back up yet.")

                val name =
                    backups.write(
                        folder.toUri(),
                        backupFileName(LocalDateTime.now()),
                        buildExport(shows, settings.currentLastCheckedAt(), Instant.now()),
                    )
                settings.recordBackupSuccess(Instant.now().toString())
                name
            }.onSuccess { onDone("Saved $it.") }
                .onFailure { onError(it.message ?: "Backup failed.") }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * The schema version whose columns a refresh is known to fill in.
         *
         * An install carrying anything lower refreshes once on the next open, whatever the
         * six-hour staleness rule says, so a column added by an upgrade is populated on the
         * screen the user just updated to see it rather than up to six hours later.
         *
         * Bump this whenever a migration adds a column TMDB is the source of. It is
         * deliberately its own number and not `ShowDatabase.VERSION`: a migration that adds
         * something the user owns rather than something TMDB sends - `dismissed.name` was
         * one - needs no refetch, and bumping it for that would refresh every library on
         * upgrade for nothing.
         */
        const val BACKFILL_VERSION = 5

        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    LibraryViewModel(
                        container.library,
                        container.settings,
                        container.tmdb,
                        container.backups,
                        container.backupSchedule,
                    ) as T
            }
    }
}
