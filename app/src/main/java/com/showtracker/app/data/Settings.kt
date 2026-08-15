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
    }

    val apiKey: Flow<String?> = context.settingsStore.data.map { it[API_KEY] }

    val lastCheckedAt: Flow<String?> = context.settingsStore.data.map { it[LAST_CHECKED_AT] }

    /** A one-shot read, for a background worker that has no reason to observe. */
    suspend fun currentApiKey(): String? = apiKey.first()

    suspend fun setApiKey(key: String) {
        context.settingsStore.edit { it[API_KEY] = key.trim() }
    }

    suspend fun clearApiKey() {
        context.settingsStore.edit { it.remove(API_KEY) }
    }

    suspend fun setLastCheckedAt(timestamp: String) {
        context.settingsStore.edit { it[LAST_CHECKED_AT] = timestamp }
    }
}
