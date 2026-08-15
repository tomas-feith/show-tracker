package com.showtracker.app.data

import com.showtracker.app.domain.TrackedShow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate

/**
 * Writes the same versioned payload the React Native build produced, so a file moves in
 * either direction between the two.
 *
 * This exists so the port does not cost a feature. Once the old app is uninstalled it is
 * the only way a library leaves the device, and a library that cannot be backed up is one
 * uninstall away from gone - which is the exact situation Phase 0 was needed to rescue.
 *
 * Deliberately NOT included: the TMDB key. It is a credential, and an export is meant to
 * leave the device.
 */
private val exportJson =
    Json {
        // Readable, because an export is a backup someone may open in a text editor to
        // check it is not empty. The size difference is irrelevant at this scale.
        prettyPrint = true

        // Null fields are omitted rather than written. Chiefly so the obsolete
        // `acknowledgedSeason` never reappears in a file this app wrote - the reader still
        // accepts it, but nothing should be producing it again. Every field on the reading
        // side has a default, so an absent key and an explicit null mean the same thing.
        explicitNulls = false

        // Everything else IS written, even where it happens to equal the declared default.
        // Without this, kotlinx-serialization drops any such field: an empty library came
        // out with no `shows` key at all, and a season with no episodes lost its
        // `episodeCount`. Round-tripping through this app's own reader would still work,
        // since it shares those defaults - which is exactly what makes the omission
        // dangerous. A backup should say what it means rather than rely on the reader
        // guessing the same way.
        encodeDefaults = true
    }

/** Build the payload. Pure, so the exact bytes can be asserted in a test. */
fun buildExport(
    shows: List<TrackedShow>,
    lastCheckedAt: String?,
    now: Instant = Instant.now(),
): String {
    val payload =
        ExportEnvelope(
            format = EXPORT_FORMAT,
            version = EXPORT_VERSION,
            exportedAt = now.toString(),
            lastCheckedAt = lastCheckedAt,
            shows = shows.map { it.toPayload() },
        )
    return exportJson.encodeToString(payload)
}

/**
 * A filename that sorts chronologically. Uses the local calendar date, matching what the
 * user would call today; the precise instant is inside the payload.
 */
fun exportFileName(today: LocalDate = LocalDate.now()): String = "show-tracker-$today.json"
