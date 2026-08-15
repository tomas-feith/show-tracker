package com.showtracker.app.data

import com.showtracker.app.domain.EpisodeRef
import com.showtracker.app.domain.Season
import com.showtracker.app.domain.TrackedShow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Identifies a payload as ours; anything else is refused rather than half-read. */
const val EXPORT_FORMAT = "showtracker-export"

/** The highest payload version this build understands. */
const val EXPORT_VERSION = 1

/**
 * Reads the JSON written by the React Native build's "Export library".
 *
 * That app and this one are separate installs with separate storage, so a file is the only
 * way a library crosses between them. The format is documented in the README.
 *
 * `isLenient` is deliberately off. A hand-edited or truncated file should fail loudly here
 * rather than produce a library that is quietly missing shows.
 */
private val json =
    Json {
        // Forward compatibility within a version: a field added by a later writer should
        // not stop an older reader, since `version` already gates real breaks.
        ignoreUnknownKeys = true
        isLenient = false
    }

@Serializable
internal data class ExportEnvelope(
    val format: String? = null,
    val version: Int = 0,
    val exportedAt: String? = null,
    val lastCheckedAt: String? = null,
    val shows: List<ShowPayload> = emptyList(),
)

@Serializable
internal data class ShowPayload(
    val id: Int,
    val name: String,
    val posterPath: String? = null,
    val firstAirDate: String? = null,
    val status: String = "",
    val seasons: List<SeasonPayload> = emptyList(),
    val lastEpisode: EpisodePayload? = null,
    val nextEpisode: EpisodePayload? = null,
    val watchedThroughSeason: Int? = null,
    val knownAiredSeason: Int? = null,
    /**
     * The single watermark used before watched-progress and notify-state were split.
     * Present only in a file written by a pre-split install; carried here so such a file
     * still imports rather than silently resetting both watermarks to zero.
     */
    @SerialName("acknowledgedSeason")
    val legacyWatermark: Int? = null,
    val addedAt: String = "",
    val lastCheckedAt: String? = null,
)

@Serializable
internal data class SeasonPayload(
    val seasonNumber: Int,
    val name: String = "",
    val airDate: String? = null,
    val episodeCount: Int = 0,
)

@Serializable
internal data class EpisodePayload(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val name: String = "",
    val airDate: String? = null,
)

/** The outcome of reading an export file. */
sealed interface ImportResult {
    data class Success(
        val shows: List<TrackedShow>,
        val lastCheckedAt: String?,
    ) : ImportResult

    /** [reason] is written to be shown to the user as-is. */
    data class Failure(
        val reason: String,
    ) : ImportResult
}

/**
 * Parse an export.
 *
 * Every failure mode returns a [ImportResult.Failure] rather than throwing, because every
 * one of them is reachable by a user picking the wrong file from the document picker, and
 * none of them is a programming error.
 */
@Suppress("ReturnCount")
fun parseExport(text: String): ImportResult {
    val payload =
        runCatching { json.decodeFromString<ExportEnvelope>(text) }.getOrNull()
            ?: return ImportResult.Failure("That file is not readable as Show Tracker data.")

    if (payload.format != EXPORT_FORMAT) {
        return ImportResult.Failure("That file is not a Show Tracker export.")
    }

    // Refuse a newer payload instead of guessing. A version bump means a field this build
    // does not know about, and importing anyway would drop it without saying so.
    if (payload.version > EXPORT_VERSION) {
        return ImportResult.Failure(
            "That export was written by a newer version of the app (format ${payload.version}).",
        )
    }

    if (payload.shows.isEmpty()) {
        return ImportResult.Failure("That export contains no shows.")
    }

    // Last one wins on a duplicate id, matching how the library itself is keyed. A file
    // with duplicates is malformed, but dropping the extras beats a crash on insert.
    val shows =
        payload.shows
            .associateBy { it.id }
            .values
            .map { it.toDomain() }

    return ImportResult.Success(shows, payload.lastCheckedAt)
}

internal fun ShowPayload.toDomain(): TrackedShow =
    TrackedShow(
        id = id,
        name = name,
        posterPath = posterPath,
        firstAirDate = firstAirDate,
        status = status,
        seasons = seasons.map { Season(it.seasonNumber, it.name, it.airDate, it.episodeCount) },
        lastEpisode = lastEpisode?.toDomain(),
        nextEpisode = nextEpisode?.toDomain(),
        // `?:` rather than a truthiness check, so a deliberate 0 ("not started") survives
        // the import instead of being replaced by the legacy value.
        watchedThroughSeason = watchedThroughSeason ?: legacyWatermark ?: 0,
        knownAiredSeason = knownAiredSeason ?: legacyWatermark ?: 0,
        addedAt = addedAt,
        lastCheckedAt = lastCheckedAt,
    )

internal fun EpisodePayload.toDomain(): EpisodeRef =
    EpisodeRef(seasonNumber, episodeNumber, name, airDate)

// --- encode side ---

internal fun TrackedShow.toPayload(): ShowPayload =
    ShowPayload(
        id = id,
        name = name,
        posterPath = posterPath,
        firstAirDate = firstAirDate,
        status = status,
        seasons =
            seasons.map {
                SeasonPayload(
                    it.seasonNumber,
                    it.name,
                    it.airDate,
                    it.episodeCount,
                )
            },
        lastEpisode = lastEpisode?.toPayload(),
        nextEpisode = nextEpisode?.toPayload(),
        watchedThroughSeason = watchedThroughSeason,
        knownAiredSeason = knownAiredSeason,
        // Never written. It exists only so a file from a pre-split install can still be
        // read; writing it back would resurrect a shape this app stopped using.
        legacyWatermark = null,
        addedAt = addedAt,
        lastCheckedAt = lastCheckedAt,
    )

private fun EpisodeRef.toPayload(): EpisodePayload =
    EpisodePayload(seasonNumber, episodeNumber, name, airDate)
