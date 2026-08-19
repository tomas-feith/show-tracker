package com.showtracker.app.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * Guards the upgrade path for the database holding the user's watch progress.
 *
 * `watchedThroughSeason` exists nowhere else - every other column could be refetched from
 * TMDB - and destructive fallback is deliberately disabled, so a missing or wrong migration
 * means a crash on launch rather than a silent reset. This test makes that a build failure.
 *
 * The schema JSONs under `app/schemas` are what [MigrationTestHelper] replays against, so
 * they are committed; a missing export would leave these tests with nothing to diff.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private companion object {
        const val TEST_DB = "migration-test.db"

        val MIGRATIONS = ShowDatabase.MIGRATIONS.toTypedArray()
    }

    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            ShowDatabase::class.java,
            emptyList(),
            FrameworkSQLiteOpenHelperFactory(),
        )

    /**
     * The migration that adds `inProgressSeason` must not disturb a single existing row.
     *
     * This is the first real upgrade an installed library will take, and the only copy of
     * `watchedThroughSeason` is in it. The new column has to arrive as NULL - "nothing in
     * progress" - which is what was true before it existed.
     */
    @Test
    @Throws(IOException::class)
    fun addingInProgressSeasonKeepsExistingProgress() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO shows
                    (id, name, posterPath, firstAirDate, status,
                     watchedThroughSeason, knownAiredSeason, addedAt, lastCheckedAt)
                VALUES (1, 'Shōgun', NULL, '2024-02-27', 'Returning Series',
                        3, 4, '2026-01-05T10:00:00.000Z', NULL)
                """.trimIndent(),
            )
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, *MIGRATIONS)

        val query =
            "SELECT name, watchedThroughSeason, knownAiredSeason, inProgressSeason " +
                "FROM shows WHERE id = 1"

        db.query(query).use { cursor ->
            assertTrue("the row did not survive the migration", cursor.moveToFirst())
            assertEquals("Shōgun", cursor.getString(0))
            assertEquals("watch progress was disturbed", 3, cursor.getInt(1))
            assertEquals(4, cursor.getInt(2))
            assertTrue("inProgressSeason should arrive null", cursor.isNull(3))
        }
    }

    @Test
    @Throws(IOException::class)
    fun currentSchemaOpensCleanly() {
        helper.createDatabase(TEST_DB, ShowDatabase.VERSION).use { db ->
            assertTrue("shows table missing", db.hasTable("shows"))
            assertTrue("seasons table missing", db.hasTable("seasons"))
        }
    }

    /**
     * A season row must not outlive the show it belongs to.
     *
     * The cascade is what stops a removed show leaving orphan seasons behind, and an orphan
     * would be invisible until it silently rejoined a future show that reused the id.
     */
    @Test
    @Throws(IOException::class)
    fun deletingAShowCascadesToItsSeasons() {
        helper.createDatabase(TEST_DB, ShowDatabase.VERSION).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                """
                INSERT INTO shows
                    (id, name, posterPath, firstAirDate, status,
                     watchedThroughSeason, knownAiredSeason, addedAt, lastCheckedAt)
                VALUES (1, 'Shōgun', NULL, '2024-02-27', 'Returning Series',
                        0, 1, '2026-01-05T10:00:00.000Z', NULL)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO seasons (showId, seasonNumber, name, airDate, episodeCount)
                VALUES (1, 1, 'Season 1', '2024-02-27', 10)
                """.trimIndent(),
            )

            db.execSQL("DELETE FROM shows WHERE id = 1")

            db.query("SELECT COUNT(*) FROM seasons WHERE showId = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("season rows outlived their show", 0, cursor.getInt(0))
            }
        }
    }

    /**
     * Every migration in [ShowDatabase.MIGRATIONS] must be contiguous and land exactly on
     * the declared version. A gap here would fail at runtime on a real upgrade.
     */
    @Test
    fun migrationsCoverEveryVersion() {
        val migrations = ShowDatabase.MIGRATIONS.sortedBy { it.startVersion }

        var version = 1
        migrations.forEach { migration ->
            assertEquals(
                "migration ${migration.startVersion}->${migration.endVersion} " +
                    "does not follow version $version",
                version,
                migration.startVersion,
            )
            version = migration.endVersion
        }

        assertEquals(
            "MIGRATIONS must reach the declared database version",
            ShowDatabase.VERSION,
            version,
        )
    }

    private fun SupportSQLiteDatabase.hasTable(name: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(name))
            .use { it.count > 0 }
}
