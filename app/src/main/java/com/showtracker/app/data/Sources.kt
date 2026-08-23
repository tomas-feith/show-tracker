package com.showtracker.app.data

import com.showtracker.app.domain.TrackedShow
import kotlinx.coroutines.flow.Flow

// Narrow seams onto the two collaborators a screen cannot construct in a unit test.
//
// Settings wraps a DataStore over a Context and LibraryRepository wraps a Room DAO, so a
// ViewModel depending on the concrete types can only be exercised on a device. Two of the
// bugs found in the discovery screen - a cancelled load leaving its tab spinning for ever,
// and every seed failing rendering as an empty library - were logic errors that a JVM test
// would have caught, and neither had one because there was no way to write it.
//
// Interfaces rather than open classes, and named for what the caller needs rather than for
// what implements them: a ViewModel that can only read the key cannot accidentally start
// writing it.

/** The TMDB key, as anything that only needs to read it sees it. */
interface ApiKeySource {
    val apiKey: Flow<String?>
}

/** What the discovery screen needs from the library. */
interface DiscoverLibrary {
    suspend fun all(): List<TrackedShow>

    suspend fun dismissedIds(): Set<Int>

    suspend fun dismiss(
        id: Int,
        name: String,
        at: String,
    )
}
