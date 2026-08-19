package com.showtracker.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ShowDao {
    @Transaction
    @Query("SELECT * FROM shows")
    fun observeAll(): Flow<List<ShowWithSeasons>>

    @Transaction
    @Query("SELECT * FROM shows")
    suspend fun getAll(): List<ShowWithSeasons>

    @Transaction
    @Query("SELECT * FROM shows WHERE id = :id")
    suspend fun getById(id: Int): ShowWithSeasons?

    @Query("SELECT COUNT(*) FROM shows")
    suspend fun count(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM shows WHERE id = :id)")
    suspend fun exists(id: Int): Boolean

    @Upsert
    suspend fun upsertShow(show: ShowEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSeasons(seasons: List<SeasonEntity>)

    @Query("DELETE FROM seasons WHERE showId = :showId")
    suspend fun deleteSeasonsFor(showId: Int)

    @Query("DELETE FROM shows WHERE id = :id")
    suspend fun deleteShow(id: Int)

    @Query("DELETE FROM shows")
    suspend fun deleteAllShows()

    /**
     * Move the watched-through watermark, dropping an in-progress marker the watermark has
     * now reached.
     *
     * Both in one statement rather than two writes, so no observer of the library flow can
     * ever see a show that is simultaneously finished with season 3 and partway through it.
     * Seasons above the new watermark keep their marker: moving the watermark backwards to
     * correct a mis-tap must not also forget where the user had got to.
     */
    @Query(
        """
        UPDATE shows
        SET watchedThroughSeason = :season,
            inProgressSeason =
                CASE WHEN inProgressSeason <= :season THEN NULL ELSE inProgressSeason END
        WHERE id = :id
        """,
    )
    suspend fun setWatchedThrough(
        id: Int,
        season: Int,
    )

    /**
     * Set or clear the season the user is partway through. Null means nothing in progress.
     *
     * The watermark check is inside the statement rather than a read before it: a season at
     * or below the watched-through mark is finished, not underway, so it clears the marker
     * instead of storing a state every reader would only ignore. Doing that as a read and
     * then a write would leave a window where a concurrent `setWatchedThrough` decided the
     * comparison was against a watermark that no longer applies.
     *
     * A null [season] fails the comparison too, which is exactly the clear it asks for.
     */
    @Query(
        """
        UPDATE shows
        SET inProgressSeason =
            CASE WHEN :season > watchedThroughSeason THEN :season ELSE NULL END
        WHERE id = :id
        """,
    )
    suspend fun setInProgressSeason(
        id: Int,
        season: Int?,
    )

    /**
     * Write a show and replace its season list in one transaction.
     *
     * The seasons are deleted and reinserted rather than upserted, because TMDB can remove
     * a season as well as add one. Upserting alone would leave a withdrawn season behind
     * forever, and a stale row here inflates the backlog count the whole app is built on.
     */
    @Transaction
    suspend fun saveShow(
        show: ShowEntity,
        seasons: List<SeasonEntity>,
    ) {
        upsertShow(show)
        deleteSeasonsFor(show.id)
        insertSeasons(seasons)
    }

    /**
     * Replace the entire library, for an import.
     *
     * One transaction so a failure part-way cannot leave a half-imported library: either
     * every show arrives or the old contents are still there. `DELETE FROM shows` cascades
     * to `seasons`, so no orphan rows survive.
     */
    @Transaction
    suspend fun replaceLibrary(entries: List<Pair<ShowEntity, List<SeasonEntity>>>) {
        deleteAllShows()
        entries.forEach { (show, seasons) ->
            upsertShow(show)
            insertSeasons(seasons)
        }
    }
}
