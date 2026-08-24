package com.showtracker.app.ui.components

import com.showtracker.app.data.ApiKeySource
import com.showtracker.app.domain.SearchResult
import com.showtracker.app.domain.ShowDetail
import com.showtracker.app.network.TmdbClient
import com.showtracker.app.ui.catchingUserFacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The show being looked at before the user commits to following it.
 *
 * The identifying fields come from the list that was tapped, so the sheet can draw its
 * header immediately; [detail] arrives from the network a moment later and fills in the
 * rest.
 */
data class Preview(
    val id: Int,
    val name: String,
    val posterPath: String?,
    val firstAirDate: String?,
    /**
     * The score off the search hit, so it is on screen before the detail fetch returns.
     *
     * The list the user tapped already showed it, and a figure that vanished on opening
     * the sheet and came back a moment later would read as the app changing its mind. TMDB
     * returns the same numbers in both responses, so nothing moves when [detail] lands.
     */
    val voteAverage: Double = 0.0,
    val voteCount: Int = 0,
    val loading: Boolean = true,
    val detail: ShowDetail? = null,
    val error: String? = null,
) {
    /** Prefer the detail fetch once it is in: it is the fresher of two identical figures. */
    val score: Pair<Double, Int>
        get() =
            detail
                ?.takeIf { it.voteCount > 0 }
                ?.let { it.voteAverage to it.voteCount }
                ?: (voteAverage to voteCount)
}

/**
 * Opening, filling and closing the preview sheet.
 *
 * A plain object rather than a ViewModel, held by whichever ViewModel owns a screen that
 * shows the sheet. Discovery and search both need identical behaviour - and got it by
 * being pointed at the same thing, rather than by one of them growing a second, subtly
 * different copy of the fetch and its two race guards.
 */
class PreviewController(
    private val tmdb: TmdbClient,
    private val settings: ApiKeySource,
    private val scope: CoroutineScope,
) {
    private val _preview = MutableStateFlow<Preview?>(null)
    val preview: StateFlow<Preview?> = _preview.asStateFlow()

    fun open(result: SearchResult) {
        _preview.value =
            Preview(
                id = result.id,
                name = result.name,
                posterPath = result.posterPath,
                firstAirDate = result.firstAirDate,
                voteAverage = result.voteAverage,
                voteCount = result.voteCount,
            )

        scope.launch {
            catchingUserFacing {
                val key = settings.apiKey.first() ?: error("No TMDB key configured.")
                tmdb.fetchShow(key, result.id)
            }.onSuccess { detail ->
                update(result.id) { it.copy(loading = false, detail = detail) }
            }.onFailure { failure ->
                update(result.id) {
                    it.copy(
                        loading = false,
                        error = failure.message ?: "Could not load that show.",
                    )
                }
            }
        }
    }

    fun close() {
        _preview.value = null
    }

    /**
     * Apply [block] only while [id] is still the show on screen.
     *
     * A slow fetch landing after the sheet was closed, or after a different show was
     * opened, would otherwise fill it with the wrong synopsis and episode counts.
     */
    private fun update(
        id: Int,
        block: (Preview) -> Preview,
    ) {
        _preview.update { current ->
            if (current?.id != id) current else block(current)
        }
    }
}
