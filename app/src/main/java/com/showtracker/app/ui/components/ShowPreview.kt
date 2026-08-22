package com.showtracker.app.ui.components

import com.showtracker.app.data.Settings
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
    val loading: Boolean = true,
    val detail: ShowDetail? = null,
    val error: String? = null,
)

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
    private val settings: Settings,
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
