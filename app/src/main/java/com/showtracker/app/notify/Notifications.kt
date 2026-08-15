package com.showtracker.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.showtracker.app.MainActivity
import com.showtracker.app.R
import com.showtracker.app.domain.Discovery

const val CHANNEL_ID = "new-seasons"

/**
 * One id, reused. A second batch of discoveries replaces the first rather than stacking:
 * the notification says "what is out that you have not seen", and two of those on the
 * shade would be two answers to the same question.
 */
private const val NOTIFICATION_ID = 1

/** How many shows are named before the text falls back to "and more". */
private const val MAX_NAMED = 4

/** Title and body for a batch of discoveries, kept pure so the wording can be tested. */
data class DiscoveryText(
    val title: String,
    val body: String,
)

/**
 * Announce newly found seasons.
 *
 * Several at once collapse into a single notification, because a batch of five separate
 * alerts is noise rather than news.
 */
fun discoveryText(discoveries: List<Discovery>): DiscoveryText? {
    val first = discoveries.firstOrNull() ?: return null

    val title =
        if (discoveries.size == 1) {
            "${first.show.name} - new season"
        } else {
            "${discoveries.size} shows have new seasons"
        }

    val body =
        if (discoveries.size == 1) {
            "Season ${first.season.seasonNumber} is out."
        } else {
            discoveries
                .take(MAX_NAMED)
                .joinToString(", ") { "${it.show.name} S${it.season.seasonNumber}" }
                .plus(if (discoveries.size > MAX_NAMED) ", and more" else "")
        }

    return DiscoveryText(title, body)
}

/**
 * Create the channel.
 *
 * Safe to call repeatedly - creating an existing channel is a no-op, and the user's own
 * changes to importance or sound are never overwritten by it.
 */
fun ensureChannel(context: Context) {
    val channel =
        NotificationChannel(
            CHANNEL_ID,
            "New seasons",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Tells you when a season of a show you follow has aired."
        }
    NotificationManagerCompat.from(context).createNotificationChannel(channel)
}

/**
 * Whether the app may post at all.
 *
 * POST_NOTIFICATIONS only exists from API 33. Below that the permission is granted at
 * install time and there is nothing to ask for, so the answer is simply yes - the version
 * check is the behaviour, not a formality to satisfy Lint.
 */
fun canPostNotifications(context: Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        true
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

/**
 * True when there is a runtime permission to ask the user for at all.
 *
 * Annotated so Lint can follow the version check through the call and know that a caller
 * inside this branch is on API 33 or later. Without it the caller has to repeat the
 * SDK_INT test purely to satisfy the tool.
 */
@ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
fun notificationPermissionIsRuntime(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Post the batch, or do nothing if there is nothing to say or no permission to say it.
 *
 * Silently doing nothing is correct here: this runs from a background worker, where there
 * is no screen to explain a refusal to, and the library has already been updated regardless.
 */
fun notifyDiscoveries(
    context: Context,
    discoveries: List<Discovery>,
) {
    val text = discoveryText(discoveries) ?: return

    // Spelled out here rather than delegated to canPostNotifications, which is the same
    // check: Lint verifies the guard only when it can see it in the same function as the
    // notify() call, and a permission this app genuinely may not hold is worth having
    // checked by the tool rather than only by the author.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    ensureChannel(context)

    val intent =
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
    val pending =
        PendingIntent.getActivity(
            context,
            0,
            intent,
            // Immutable because nothing is filled in later, and required from API 31.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    val notification =
        NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text.title)
            .setContentText(text.body)
            // The body names up to four shows and will not fit on one line.
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

    NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
}
