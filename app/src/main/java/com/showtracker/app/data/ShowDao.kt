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

    @Query("UPDATE shows SET watchedThroughSeason = :season WHERE id = :id")
    suspend fun setWatchedThrough(
        id: Int,
        season: Int,
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
