package com.showtracker.app.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Naming and retention for the scheduled export.
//
// Android's own backup covers losing the phone, but it keeps one snapshot and restores only
// onto a fresh install. It cannot answer "I marked the wrong season watched a fortnight
// ago". These files can: each run drops a dated export into a folder the user chose, and
// any of them can be read back through the existing import.
//
// The pure parts live here so retention can be tested without a filesystem. Deleting the
// wrong file in a folder the user picked - which may well be a folder with other things in
// it - is the failure worth designing against.

/** Distinguishes our files from anything else in the user's folder. */
private const val PREFIX = "show-tracker-backup-"

private const val SUFFIX = ".json"

/**
 * Seconds are in the name, not just the date.
 *
 * Two runs on one day are possible - a "back up now" tap after the scheduled run - and a
 * date-only name would make the second silently overwrite the first, which is the one thing
 * a backup must never do. Dashes rather than colons because a colon is not a legal filename
 * character on most of the places these end up.
 */
private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

/** How many exports to keep. Roughly a fortnight of daily runs. */
const val BACKUPS_KEPT = 14

fun backupFileName(now: LocalDateTime): String = "$PREFIX${now.format(STAMP)}$SUFFIX"

/**
 * Locale pinned rather than left to the device's.
 *
 * Every other string in this app is English, so a month name that follows the phone's
 * locale would be the one translated word on the screen - and it would make this
 * untestable, since the expected output would depend on where the test ran.
 */
private val SHOWN: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withLocale(Locale.UK)

/**
 * The stored timestamp as something a person reads.
 *
 * Stored as a UTC instant, because that is unambiguous and sorts; shown in the local
 * calendar, because "05:53Z" is not an answer to "when did this last work" for someone
 * looking at a clock that says 08:22. The sub-second precision is dropped for the same
 * reason - nobody is timing it.
 *
 * An unparseable value is returned as-is rather than hidden: it means something wrote a
 * shape this app did not, and showing it is how that gets noticed.
 */
fun describeBackupTime(
    iso: String,
    zone: ZoneId = ZoneId.systemDefault(),
): String = runCatching { Instant.parse(iso).atZone(zone).format(SHOWN) }.getOrDefault(iso)

/** True for a name this app wrote, and only for those. */
fun isBackupFileName(name: String): Boolean =
    name.startsWith(PREFIX) &&
        name.endsWith(SUFFIX) &&
        name.length == PREFIX.length + STAMP_LENGTH + SUFFIX.length

private const val STAMP_LENGTH = "yyyy-MM-dd-HHmmss".length

/**
 * Which of [existing] to delete, keeping the [keep] newest.
 *
 * Anything not matching [isBackupFileName] is ignored rather than considered old: the
 * folder belongs to the user, and a retention rule that reaches beyond the files this app
 * created would be a data-loss bug wearing a housekeeping hat.
 *
 * The timestamp format sorts lexicographically in time order, so a name sort is a date
 * sort, and no parsing - or the failure modes of parsing something unexpected - is needed.
 */
fun backupsToPrune(
    existing: List<String>,
    keep: Int = BACKUPS_KEPT,
): List<String> =
    existing
        .filter(::isBackupFileName)
        .sortedDescending()
        .drop(keep.coerceAtLeast(0))
