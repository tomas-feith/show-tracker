package com.showtracker.app.data

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
