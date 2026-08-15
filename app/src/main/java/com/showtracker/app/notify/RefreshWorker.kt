package com.showtracker.app.notify

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.showtracker.app.ShowTrackerApplication
import com.showtracker.app.domain.ShowFetcher
import com.showtracker.app.domain.refreshShows
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The periodic season check. This is what makes the app useful without a server: Android
 * wakes it, it re-reads TMDB, and it posts a local notification if anything dropped.
 *
 * Android decides when - and whether - this actually runs, and will skip it on a low
 * battery or in Doze. So it is a bonus on top of the check that happens when the app is
 * opened, never a guarantee. The React Native build said the same thing about
 * expo-background-task, which was WorkManager underneath.
 */
class RefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container =
            (applicationContext as? ShowTrackerApplication)?.container
                ?: return Result.failure()

        return try {
            val key = container.settings.currentApiKey() ?: return Result.success()
            val shows = container.library.all()
            if (shows.isEmpty()) return Result.success()

            val fetcher = ShowFetcher { ids -> container.tmdb.fetchShows(key, ids) }
            val outcome =
                refreshShows(fetcher, shows, Instant.now(), LocalDate.now())

            container.library.saveAll(outcome.shows)
            container.settings.setLastCheckedAt(Instant.now().toString())
            notifyDiscoveries(applicationContext, outcome.discoveries)

            // Per-show failures are already absorbed by refreshShows, which keeps the
            // previous data. Reporting retry for them would re-run the whole library for
            // the sake of one show that will be picked up next time anyway.
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager stopping us is not a failure; let it propagate so the job is
            // rescheduled rather than recorded as broken.
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // Never throw out of a worker: Android counts crashes against the app and backs
            // off scheduling, which quietly makes the feature stop working altogether.
            //
            // Logged rather than discarded, because there is no screen here to report to,
            // and a background refresh that silently stops working is exactly the failure
            // the user would never notice until a season had already been missed. logcat
            // is the only way to find out why afterwards.
            Log.w(TAG, "Periodic refresh failed (attempt $runAttemptCount)", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "RefreshWorker"

        const val WORK_NAME = "showtracker-periodic-refresh"

        /** Roughly twice a day. WorkManager treats this as a floor, not a promise. */
        private const val INTERVAL_HOURS = 12L

        private const val MAX_ATTEMPTS = 3

        /**
         * Register the periodic check. Safe to call on every launch.
         *
         * KEEP, not UPDATE: replacing the request on each start would reset its period, so
         * an app opened often would never sit long enough for the work to come due.
         */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<RefreshWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                    .setConstraints(
                        Constraints
                            .Builder()
                            // The React Native build had no such constraint, so a check
                            // could fire with no connection, fail every fetch and burn the
                            // slot. Waiting costs nothing: the work is not time-critical.
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build(),
                    ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
