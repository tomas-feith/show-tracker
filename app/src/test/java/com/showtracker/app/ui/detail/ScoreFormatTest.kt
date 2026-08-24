package com.showtracker.app.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The score as the detail screen writes it.
 *
 * Worth a test of its own because the obvious implementation is wrong in a way nobody
 * notices: `String.format("%.1f", 8.45)` rounds the binary double, which is a hair below
 * 8.45, and prints a figure one digit off the one TMDB shows.
 */
class ScoreFormatTest {
    @Test
    fun `rounds a half up, on the decimal value rather than the binary one`() {
        assertEquals("8.5", formatScore(8.45))
        assertEquals("7.6", formatScore(7.55))
        assertEquals("8.4", formatScore(8.44))
    }

    @Test
    fun `always shows one decimal place`() {
        // A bare "8" next to "8.4" in a list reads as a different kind of number.
        assertEquals("8.0", formatScore(8.0))
        assertEquals("10.0", formatScore(10.0))
        assertEquals("0.0", formatScore(0.0))
    }

    @Test
    fun `writes a dot regardless of the phone's locale`() {
        val previous = java.util.Locale.getDefault()
        try {
            // pt-PT writes 8,4. Every other string on the screen is hardcoded English.
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("pt-PT"))
            assertEquals("8.4", formatScore(8.42))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
