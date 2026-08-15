package com.showtracker.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * One dark palette, ported unchanged from the React Native build's `theme.ts`.
 *
 * A media library reads better dark, and committing to a single scheme keeps every
 * screen consistent without theme plumbing. Dynamic colour is deliberately not used:
 * the state colours below carry meaning, and letting the wallpaper repaint them would
 * make "behind" and "airing" stop being distinguishable at a glance.
 */
val Bg = Color(0xFF0E1116)
val Surface = Color(0xFF171B22)
val SurfaceAlt = Color(0xFF1F242D)
val Border = Color(0xFF2A313B)
val TextPrimary = Color(0xFFF2F5F9)
val TextMuted = Color(0xFF98A2B3)
val TextFaint = Color(0xFF6B7480)
val Accent = Color(0xFF4F8DF7)

/** A season is out and unwatched. */
val StateNew = Color(0xFFF5A524)

/** Currently airing, or an episode scheduled soon. */
val StateAiring = Color(0xFF31C48D)

val Danger = Color(0xFFF26D6D)

private val ShowTrackerColors =
    darkColorScheme(
        primary = Accent,
        onPrimary = Color.White,
        background = Bg,
        onBackground = TextPrimary,
        surface = Surface,
        onSurface = TextPrimary,
        surfaceVariant = SurfaceAlt,
        onSurfaceVariant = TextMuted,
        outline = Border,
        error = Danger,
        onError = Color.White,
    )

@Composable
fun ShowTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ShowTrackerColors,
        content = content,
    )
}
