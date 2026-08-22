package com.showtracker.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ShowEntity::class, SeasonEntity::class, DismissedEntity::class],
    version = ShowDatabase.VERSION,
    exportSchema = true,
)
abstract class ShowDatabase : RoomDatabase() {
    abstract fun showDao(): ShowDao

    companion object {
        const val VERSION = 4

        const val NAME = "shows.db"

        /**
         * Adds `shows.inProgressSeason`, the season the user is partway through.
         *
         * Nullable with no default, so every existing row becomes "nothing in progress",
         * which is exactly what was true before the column existed. A plain `ADD COLUMN`
         * leaves every other column, and so all of the user's progress, untouched.
         */
        private val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE shows ADD COLUMN inProgressSeason INTEGER")
                }
            }

        /**
         * Adds `shows.overview`, and the `dismissed` table behind "not interested".
         *
         * The column is NOT NULL with a `''` default so existing rows get the same value
         * a show with no synopsis would have, rather than a null the domain would have to
         * keep re-deciding about. The text is refetched on the next refresh, so the empty
         * string is a gap that closes itself rather than data that was lost.
         */
        private val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE shows ADD COLUMN overview TEXT NOT NULL DEFAULT ''",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS dismissed (" +
                            "id INTEGER NOT NULL, " +
                            "dismissedAt TEXT NOT NULL, " +
                            "PRIMARY KEY(id))",
                    )
                }
            }

        /**
         * Adds `dismissed.name`, so hidden shows can be listed and un-hidden by name.
         *
         * The table arrived one version ago and is empty on most installs, but the column
         * is added rather than the table recreated: a dismissal the user has already made
         * is a decision worth keeping, even if it comes back with a blank name.
         */
        private val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "ALTER TABLE dismissed ADD COLUMN name TEXT NOT NULL DEFAULT ''",
                    )
                }
            }

        /**
         * Schema migrations, oldest first.
         *
         * Destructive fallback is deliberately never enabled. Most of what this database
         * holds could be refetched from TMDB, but `watchedThroughSeason` could not: it is
         * the user's own record of what they have seen, exists nowhere else, and a wipe
         * would silently reset every show to "not started".
         *
         * To add one:
         *  1. Change the entities and bump [VERSION].
         *  2. Add a `Migration(n, n + 1)` here with the SQL.
         *  3. `MigrationTest` will fail until the schema JSONs and the SQL agree.
         *
         * The exported schema JSONs under `app/schemas` are committed for exactly this
         * reason: they are what the migration test diffs against.
         */

        val MIGRATIONS: List<Migration> =
            listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

        @Volatile
        private var instance: ShowDatabase? = null

        fun get(context: Context): ShowDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): ShowDatabase =
            Room
                .databaseBuilder(
                    context.applicationContext,
                    ShowDatabase::class.java,
                    NAME,
                ).apply { MIGRATIONS.forEach { addMigrations(it) } }
                .build()
    }
}
