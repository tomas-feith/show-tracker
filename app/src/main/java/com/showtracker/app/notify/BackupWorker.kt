package com.showtracker.app.notify

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.showtracker.app.ShowTrackerApplication
import com.showtracker.app.data.backupFileName
import com.showtracker.app.data.buildExport
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * The scheduled export.
 *
 * Writes the same JSON as the export button into the folder the user chose, once a day,
 * keeping the last two weeks. Android's own backup already covers a lost phone; this covers
 * the thing that one cannot, which is going back to how the library looked before a mistake.
 *
 * No network constraint: the write is to a local content provider. Whether that provider
 * then syncs the file somewhere is the sync app's business and its own schedule.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? ShowTrackerApplication)?.container
                ?: return Result.failure()

        val folder = container.settings.currentBackupFolder() ?: return Result.success()

        return try {
            val shows = container.library.all()
            // Nothing to protect yet, and writing an empty export daily would push the
            // real ones out of the retention window.
            if (shows.isEmpty()) return Result.success()

            val json =
                buildExport(
                    shows,
                    container.settings.currentLastCheckedAt(),
                    Instant.now(),
                )

            container.backups.write(
                folder.toUri(),
                backupFileName(LocalDateTime.now()),
                json,
            )
            container.settings.recordBackupSuccess(Instant.now().toString())
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Log.w(TAG, "Scheduled backup failed (attempt $runAttemptCount)", e)

            // Recorded where the user will see it, not only in logcat. The likely causes -
            // the folder was deleted, or the grant was revoked by a factory reset of the
            // provider app - are permanent until someone picks a folder again, and a
            // backup that has silently stopped is worse than one that was never set up.
            container.settings.recordBackupFailure(
                e.message ?: "The backup folder could not be written to.",
            )
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "BackupWorker"

        const val WORK_NAME = "showtracker-scheduled-backup"

        private const val INTERVAL_HOURS = 24L

        private const val MAX_ATTEMPTS = 3

        /**
         * Register the daily export, replacing any existing registration.
         *
         * UPDATE rather than KEEP, unlike [RefreshWorker]: this is called when the user
         * picks a folder, and that is exactly the moment the schedule should restart rather
         * than inherit the timing of a registration made for a folder they have replaced.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<BackupWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            .setRequiresBatteryNotLow(true)
                            .build(),
                    ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
