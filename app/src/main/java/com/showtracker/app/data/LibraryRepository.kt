package com.showtracker.app.data

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
) {
    fun observeLibrary(): Flow<List<TrackedShow>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun all(): List<TrackedShow> = dao.getAll().map { it.toDomain() }

    suspend fun get(id: Int): TrackedShow? = dao.getById(id)?.toDomain()

    suspend fun isTracked(id: Int): Boolean = dao.exists(id)

    fun observeDismissed(): Flow<Set<Int>> = dao.observeDismissed().map { it.toSet() }

    suspend fun dismissedIds(): Set<Int> = dao.dismissedIds().toSet()

    suspend fun dismiss(
        id: Int,
        at: String,
    ) {
        dao.dismiss(DismissedEntity(id, at))
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

    suspend fun saveAll(shows: List<TrackedShow>) {
        shows.forEach { save(it) }
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
