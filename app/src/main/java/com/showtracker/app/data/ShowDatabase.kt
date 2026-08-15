package com.showtracker.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

@Database(
    entities = [ShowEntity::class, SeasonEntity::class],
    version = ShowDatabase.VERSION,
    exportSchema = true,
)
abstract class ShowDatabase : RoomDatabase() {
    abstract fun showDao(): ShowDao

    companion object {
        const val VERSION = 1

        const val NAME = "shows.db"

        /**
         * Schema migrations, oldest first. Empty at version 1.
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
        val MIGRATIONS: List<Migration> = emptyList()

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
