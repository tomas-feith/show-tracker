package com.showtracker.app.data

import com.showtracker.app.domain.DismissedShow
import com.showtracker.app.domain.TrackedShow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The library, as the rest of the app sees it: domain objects, never entities.
 *
 * Keeping the Room types behind this boundary is what lets the domain layer stay a set of
 * pure functions over plain data, which is why its tests need no Android at all.
 */
class LibraryRepository(
    private val dao: ShowDao,
) : DiscoverLibrary {
    fun observeLibrary(): Flow<List<TrackedShow>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun all(): List<TrackedShow> = dao.getAll().map { it.toDomain() }

    suspend fun get(id: Int): TrackedShow? = dao.getById(id)?.toDomain()

    suspend fun isTracked(id: Int): Boolean = dao.exists(id)

    fun observeDismissed(): Flow<Set<Int>> = dao.observeDismissed().map { it.toSet() }

    override suspend fun dismissedIds(): Set<Int> = dao.dismissedIds().toSet()

    /** Hidden shows, newest first, for the list that offers to un-hide them. */
    fun observeDismissedShows(): Flow<List<DismissedShow>> =
        dao.observeDismissedEntries().map { entries ->
            entries.map { DismissedShow(it.id, it.name, it.dismissedAt) }
        }

    override suspend fun dismiss(
        id: Int,
        name: String,
        at: String,
    ) {
        dao.dismiss(DismissedEntity(id, name, at))
    }

    suspend fun undismiss(id: Int) {
        dao.undismiss(id)
    }

    suspend fun clearDismissed() {
        dao.clearDismissed()
    }

    suspend fun count(): Int = dao.count()

    suspend fun save(show: TrackedShow) {
        dao.saveShow(show.toEntity(), show.toSeasonEntities())
    }

    /**
     * Write back a whole refresh.
     *
     * Deliberately not [save] in a loop. A refresh must not write the columns the user
     * owns - it has been holding a copy of them since before the network call - and it
     * must not recreate a show removed while it ran; [ShowDao.saveRefreshed] enforces
     * both.
     */
    suspend fun saveRefreshed(shows: List<TrackedShow>) {
        shows.forEach { dao.saveRefreshed(it.toRefreshed(), it.toSeasonEntities()) }
    }

    suspend fun remove(id: Int) {
        dao.deleteShow(id)
    }

    /**
     * Record that the user has watched through [season]. Clamped at zero, since "not
     * started" is the floor and a negative watermark would make every aired season count
     * as backlog twice over.
     *
     * An in-progress marker at or below the new watermark is dropped as part of the same
     * statement; see [ShowDao.setWatchedThrough].
     */
    suspend fun setWatchedThrough(
        id: Int,
        season: Int,
    ) {
        dao.setWatchedThrough(id, season.coerceAtLeast(0))
    }

    /**
     * Record that the user is partway through [season], or clear the marker with null.
     *
     * A season already at or below the watched-through watermark cannot be in progress - it
     * is finished - and [ShowDao.setInProgressSeason] enforces that against the stored
     * watermark within the one statement.
     */
    suspend fun setInProgress(
        id: Int,
        season: Int?,
    ) {
        dao.setInProgressSeason(id, season)
    }

    /**
     * Star or unstar a show.
     *
     * A single-column update rather than a re-save; see [ShowDao.setFavourite]. Nothing
     * here clamps or corrects the value, because unlike the two watermarks there is no
     * state a star can contradict.
     */
    suspend fun setFavourite(
        id: Int,
        favourite: Boolean,
    ) {
        dao.setFavourite(id, favourite)
    }

    /**
     * Replace the whole library with an imported one.
     *
     * Replace rather than merge: an import is a restore, and merging would have to invent
     * an answer for a show present in both with different watermarks. The file is the
     * user's own most recent state, so it wins outright.
     */
    suspend fun replaceWith(shows: List<TrackedShow>) {
        dao.replaceLibrary(shows.map { it.toEntity() to it.toSeasonEntities() })
    }
}
