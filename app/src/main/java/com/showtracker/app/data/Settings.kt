package com.showtracker.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The TMDB key and the last-refresh timestamp.
 *
 * Kept out of the Room database on purpose: neither is library data, and an import replaces
 * the library wholesale. Restoring an export must not also overwrite the key the user just
 * pasted in.
 *
 * The file is excluded from Android backup (see `res/xml/backup_rules.xml`). App-private
 * storage is already sandboxed, but a backup copy travels to Google's servers and onto the
 * next device, and a credential is worth keeping on the one phone that was given it. The
 * cost of excluding it is that a restored install asks for the key again, which takes
 * seconds.
 */
private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
)

class Settings(
    private val context: Context,
) {
    private companion object {
        val API_KEY = stringPreferencesKey("tmdb_api_key")
        val LAST_CHECKED_AT = stringPreferencesKey("last_checked_at")
        val BACKUP_FOLDER = stringPreferencesKey("backup_folder_uri")
        val LAST_BACKUP_AT = stringPreferencesKey("last_backup_at")
        val LAST_BACKUP_ERROR = stringPreferencesKey("last_backup_error")
    }

    val apiKey: Flow<String?> = context.settingsStore.data.map { it[API_KEY] }

    val lastCheckedAt: Flow<String?> = context.settingsStore.data.map { it[LAST_CHECKED_AT] }

    /**
     * The SAF tree the scheduled export writes into, or null when it is switched off.
     *
     * A URI rather than a path: the app holds a persisted grant on this folder, not
     * filesystem access to it, and the grant is what the URI identifies.
     */
    val backupFolder: Flow<String?> = context.settingsStore.data.map { it[BACKUP_FOLDER] }

    val lastBackupAt: Flow<String?> = context.settingsStore.data.map { it[LAST_BACKUP_AT] }

    /**
     * Why the last scheduled export failed, or null if it did not.
     *
     * Recorded rather than only logged. A backup running unattended is trusted precisely
     * because nobody is watching it, so the one place it can report a folder that has gone
     * away is the screen the user visits when they wonder whether it is still working.
     */
    val lastBackupError: Flow<String?> = context.settingsStore.data.map { it[LAST_BACKUP_ERROR] }

    /** A one-shot read, for a background worker that has no reason to observe. */
    suspend fun currentApiKey(): String? = apiKey.first()

    /** As [currentApiKey], for the export the backup worker writes. */
    suspend fun currentLastCheckedAt(): String? = lastCheckedAt.first()

    suspend fun setApiKey(key: String) {
        context.settingsStore.edit { it[API_KEY] = key.trim() }
    }

    suspend fun clearApiKey() {
        context.settingsStore.edit { it.remove(API_KEY) }
    }

    suspend fun setLastCheckedAt(timestamp: String) {
        context.settingsStore.edit { it[LAST_CHECKED_AT] = timestamp }
    }

    /** A one-shot read, for the backup worker. */
    suspend fun currentBackupFolder(): String? = backupFolder.first()

    suspend fun setBackupFolder(uri: String) {
        context.settingsStore.edit {
            it[BACKUP_FOLDER] = uri
            // A newly chosen folder has not failed yet, and carrying the previous folder's
            // complaint over to it would be a lie the user cannot act on.
            it.remove(LAST_BACKUP_ERROR)
        }
    }

    suspend fun clearBackupFolder() {
        context.settingsStore.edit {
            it.remove(BACKUP_FOLDER)
            it.remove(LAST_BACKUP_AT)
            it.remove(LAST_BACKUP_ERROR)
        }
    }

    suspend fun recordBackupSuccess(timestamp: String) {
        context.settingsStore.edit {
            it[LAST_BACKUP_AT] = timestamp
            it.remove(LAST_BACKUP_ERROR)
        }
    }

    suspend fun recordBackupFailure(reason: String) {
        context.settingsStore.edit { it[LAST_BACKUP_ERROR] = reason }
    }
}
