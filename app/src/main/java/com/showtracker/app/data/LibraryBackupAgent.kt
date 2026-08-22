package com.showtracker.app.data

import android.app.backup.BackupAgentHelper
import android.app.backup.FullBackupDataOutput
import android.util.Log

/**
 * Folds the write-ahead log into the database before Android copies it.
 *
 * Room runs in WAL mode, so a committed transaction is durable in `shows.db-wal` long
 * before it reaches `shows.db`; SQLite checkpoints on its own schedule, not on ours. A
 * backup taken without checkpointing captures the database as of whenever that last
 * happened, and quietly loses every change since. The gap is not marginal: a library
 * populated in one sitting measured 4 KB in `shows.db` against 194 KB in `shows.db-wal`.
 *
 * `PRAGMA wal_checkpoint(TRUNCATE)` moves the log's contents into the database and empties
 * it, so what the backup copies is the whole library and not a stale prefix of it. The
 * `-wal` and `-shm` files are backed up too - see `res/xml/data_extraction_rules.xml` - so
 * that a restore is still correct on any path where this agent does not run.
 *
 * [BackupAgentHelper] rather than `BackupAgent` only to inherit no-op key/value handling.
 * This app has nothing to back up that way; everything travels as full-data files.
 */
class LibraryBackupAgent : BackupAgentHelper() {
    override fun onFullBackup(data: FullBackupDataOutput?) {
        checkpoint()
        super.onFullBackup(data)
    }

    /**
     * Never throws.
     *
     * A backup agent that fails takes the whole backup down with it, so a checkpoint that
     * cannot run has to degrade to backing up an out-of-date database rather than to
     * backing up nothing at all. The `-wal` file travelling alongside is what makes that
     * degradation survivable.
     */
    private fun checkpoint() {
        try {
            ShowDatabase
                .get(applicationContext)
                .openHelper
                .writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Could not checkpoint before backup; backing up as-is", e)
        }
    }

    private companion object {
        const val TAG = "LibraryBackupAgent"
    }
}
