package com.showtracker.app.notify

import android.content.Context

/**
 * Starting and stopping the daily backup, without handing a [Context] to a ViewModel.
 *
 * A ViewModel outlives the activity that created it, so a Context field in one is a leak
 * waiting to happen - which Android Lint rejects outright. Scheduling genuinely needs a
 * context, so it lives here instead: one object, built once from the application context by
 * the container, whose lifetime is the process either way.
 */
class BackupSchedule(
    private val context: Context,
) {
    /**
     * Cancel and re-enqueue, rather than replacing the request in place.
     *
     * Called when the user picks a folder, which is exactly when the next run should be
     * measured from now instead of inheriting whatever was left of the previous folder's
     * day.
     */
    fun restart() {
        BackupWorker.cancel(context)
        BackupWorker.schedule(context)
    }

    fun stop() {
        BackupWorker.cancel(context)
    }
}
