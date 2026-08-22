package com.showtracker.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The two writes that move the user's position, against real SQLite.
 *
 * Both enforce the same rule - a season at or below the watched-through watermark is
 * finished, so it cannot also be in progress - and both do it inside a single statement
 * rather than by reading the row first. That is only worth asserting where the SQL actually
 * runs: a read-then-write would pass every unit test and still leave a window for a
 * concurrent write to slip through.
 */
@RunWith(AndroidJUnit4::class)
class ShowDaoTest {
    private lateinit var db: ShowDatabase
    private lateinit var dao: ShowDao

    private fun show(
        watchedThroughSeason: Int = 0,
        inProgressSeason: Int? = null,
    ) = ShowEntity(
        id = 1,
        name = "Shōgun",
        overview = "A synopsis.",
        posterPath = null,
        firstAirDate = "2024-02-27",
        status = "Returning Series",
        watchedThroughSeason = watchedThroughSeason,
        inProgressSeason = inProgressSeason,
        knownAiredSeason = 2,
        addedAt = "2026-01-05T10:00:00.000Z",
        lastCheckedAt = null,
    )

    private fun stored(): ShowEntity = runBlocking { checkNotNull(dao.getById(1)).show }

    @Before
    fun open() {
        db =
            Room
                .inMemoryDatabaseBuilder(
                    InstrumentationRegistry.getInstrumentation().targetContext,
                    ShowDatabase::class.java,
                ).build()
        dao = db.showDao()
    }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun marksASeasonAboveTheWatermarkAsInProgress() =
        runBlocking {
            dao.upsertShow(show(watchedThroughSeason = 2))
            dao.setInProgressSeason(1, 3)
            assertEquals(3, stored().inProgressSeason)
        }

    @Test
    fun clearsTheMarkerOnANullSeason() =
        runBlocking {
            dao.upsertShow(show(watchedThroughSeason = 2, inProgressSeason = 3))
            dao.setInProgressSeason(1, null)
            assertNull(stored().inProgressSeason)
        }

    @Test
    fun refusesAMarkerOnASeasonAlreadyWatchedThrough() =
        runBlocking {
            // Compared against the stored watermark, not one read a moment earlier.
            dao.upsertShow(show(watchedThroughSeason = 3))
            dao.setInProgressSeason(1, 3)
            assertNull(stored().inProgressSeason)
        }

    @Test
    fun droppingTheMarkerHappensWithTheWatermarkMove() =
        runBlocking {
            dao.upsertShow(show(watchedThroughSeason = 2, inProgressSeason = 3))
            dao.setWatchedThrough(1, 3)

            val row = stored()
            assertEquals(3, row.watchedThroughSeason)
            assertNull("a finished season cannot still be in progress", row.inProgressSeason)
        }

    @Test
    fun keepsAMarkerAboveTheNewWatermark() =
        runBlocking {
            // Moving the watermark backwards to undo a mis-tap must not also forget where
            // the user had got to.
            dao.upsertShow(show(watchedThroughSeason = 2, inProgressSeason = 4))
            dao.setWatchedThrough(1, 1)

            val row = stored()
            assertEquals(1, row.watchedThroughSeason)
            assertEquals(4, row.inProgressSeason)
        }
}
