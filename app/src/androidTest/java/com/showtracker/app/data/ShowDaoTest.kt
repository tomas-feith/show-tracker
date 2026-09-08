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
 * The writes that move the user's position, and the one that must not, against real SQLite.
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
        favourite: Boolean = false,
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
        voteAverage = 8.4,
        voteCount = 4321,
        genres = "Drama",
        episodeRunTime = 55,
        type = "Miniseries",
        numberOfEpisodes = 10,
        favourite = favourite,
    )

    private fun stored(): ShowEntity = runBlocking { checkNotNull(dao.getById(1)).show }

    /** The same show as TMDB would hand it back: new metadata, nothing the user owns. */
    private fun refreshed() =
        RefreshedShow(
            id = 1,
            name = "Shōgun",
            overview = "A fresher synopsis.",
            posterPath = "/new.jpg",
            firstAirDate = "2024-02-27",
            status = "Ended",
            lastEpisode = EpisodeColumns(2, 10, "Finale", "2026-09-01"),
            nextEpisode = EpisodeColumns(),
            knownAiredSeason = 2,
            lastCheckedAt = "2026-09-08T12:00:00Z",
            voteAverage = 8.9,
            voteCount = 5000,
            genres = "Drama",
            episodeRunTime = 58,
            type = "Miniseries",
            numberOfEpisodes = 20,
        )

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

    /**
     * The bug this exists for: a refresh reads the library, spends seconds in TMDB, and
     * writes back. Anything the user did in those seconds - starring a show, marking a
     * season watched - was overwritten by the snapshot the refresh started from. The
     * partial update names only TMDB's columns, so it cannot.
     */
    @Test
    fun refreshingLeavesEverythingTheUserOwnsAlone() =
        runBlocking {
            dao.upsertShow(
                show(watchedThroughSeason = 2, inProgressSeason = 3, favourite = true),
            )

            dao.saveRefreshed(refreshed(), emptyList())

            val row = stored()
            assertEquals("the star was clobbered", true, row.favourite)
            assertEquals("watch progress was clobbered", 2, row.watchedThroughSeason)
            assertEquals("the in-progress marker was clobbered", 3, row.inProgressSeason)
            assertEquals(
                "addedAt is not the refresh's to change",
                "2026-01-05T10:00:00.000Z",
                row.addedAt,
            )

            // And it did write what it is for.
            assertEquals("A fresher synopsis.", row.overview)
            assertEquals("Ended", row.status)
            assertEquals(8.9, row.voteAverage, 0.001)
            assertEquals(20, row.numberOfEpisodes)
            assertEquals(2, row.knownAiredSeason)
        }

    /**
     * A show removed while a refresh was in flight is still in the list it is writing back.
     * An upsert would put it back on the screen the user just removed it from.
     */
    @Test
    fun refreshingDoesNotResurrectARemovedShow() =
        runBlocking {
            dao.upsertShow(show())
            dao.deleteShow(1)

            dao.saveRefreshed(
                refreshed(),
                listOf(SeasonEntity(1, 1, "Season 1", "2024-02-27", 10)),
            )

            assertNull(dao.getById(1))
            // And no orphan seasons were left behind pointing at a show that is not there.
            assertEquals(0, dao.seasonCountFor(1))
        }

    @Test
    fun starsAndUnstarsWithoutTouchingProgress() =
        runBlocking {
            dao.upsertShow(show(watchedThroughSeason = 2, inProgressSeason = 3))

            dao.setFavourite(1, true)
            assertEquals(true, stored().favourite)
            // The single-column update is the point: the user's position must be exactly
            // where it was, not wherever the caller's copy of the row happened to say.
            assertEquals(2, stored().watchedThroughSeason)
            assertEquals(3, stored().inProgressSeason)

            dao.setFavourite(1, false)
            assertEquals(false, stored().favourite)
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
