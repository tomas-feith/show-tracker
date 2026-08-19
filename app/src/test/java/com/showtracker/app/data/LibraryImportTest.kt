package com.showtracker.app.data

import com.showtracker.app.domain.hasAired
import com.showtracker.app.domain.latestAiredSeason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LibraryImportTest {
    private val today: LocalDate = LocalDate.parse("2026-08-14")

    /**
     * Read as UTF-8 explicitly rather than through the platform default. On Windows that
     * default is cp1252, which cannot represent U+014D and would turn "Shōgun" into
     * mojibake - and a library full of corrupted titles is the kind of failure that only
     * shows up after the old app has already been uninstalled.
     */
    private fun sample(): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("export-v1-sample.json")) {
            "export-v1-sample.json missing from test resources"
        }.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun success(): ImportResult.Success = parseExport(sample()) as ImportResult.Success

    @Test
    fun `reads every show in the export`() {
        val result = success()
        assertEquals(3, result.shows.size)
        assertEquals("2026-08-15T14:42:15.229Z", result.lastCheckedAt)
    }

    @Test
    fun `carries non-ASCII titles through intact`() {
        val names = success().shows.map { it.name }
        assertTrue("Shōgun" in names)
        assertTrue("Glória" in names)
        // The specific code points that a cp1252 round trip would destroy.
        assertTrue(names.any { it.contains('ō') })
        assertTrue(names.any { it.contains('ó') })
    }

    @Test
    fun `keeps the two watermarks separate`() {
        val glory = success().shows.single { it.id == 2 }
        // A deliberate zero: not started, but the app already knows season 4 aired.
        assertEquals(0, glory.watchedThroughSeason)
        assertEquals(4, glory.knownAiredSeason)
    }

    @Test
    fun `splits a pre-split install's single watermark into both`() {
        val legacy = success().shows.single { it.id == 3 }
        assertEquals(3, legacy.watchedThroughSeason)
        assertEquals(3, legacy.knownAiredSeason)
    }

    @Test
    fun `preserves season lists including specials and placeholders`() {
        val shogun = success().shows.single { it.id == 1 }
        assertEquals(3, shogun.seasons.size)

        // Season 0 is stored but never counts as aired.
        assertFalse(hasAired(shogun.seasons.single { it.seasonNumber == 0 }, today))
        // An announced season with no date and no episodes is not aired either.
        assertFalse(hasAired(shogun.seasons.single { it.seasonNumber == 2 }, today))
        assertEquals(1, latestAiredSeason(shogun.seasons, today)?.seasonNumber)
    }

    @Test
    fun `keeps nullable episode markers nullable`() {
        val shows = success().shows
        assertNull(shows.single { it.id == 1 }.nextEpisode)
        assertNull(shows.single { it.id == 2 }.lastEpisode)
        assertEquals(2, shows.single { it.id == 3 }.nextEpisode?.seasonNumber)
    }

    @Test
    fun `keeps a null poster null rather than inventing a path`() {
        // 0% of the real library has a null poster, which proves nothing about whether one
        // can occur - TMDB returns null for a show with no artwork.
        assertNull(success().shows.single { it.id == 2 }.posterPath)
    }

    @Test
    fun `ignores fields a later writer added`() {
        // The sample carries an unknown key on show 3; it must not fail the parse.
        assertEquals(3, success().shows.size)
    }

    @Test
    fun `a file written before in-progress marking existed has nothing in progress`() {
        // Every show in the sample predates the field. Absent must mean "nothing in
        // progress" rather than a season number invented from the watermark.
        assertTrue(success().shows.all { it.inProgressSeason == null })
    }

    @Test
    fun `reads an in-progress marker when the file carries one`() {
        val text =
            """
            {"format":"showtracker-export","version":1,"shows":[
              {"id":7,"name":"Underway","addedAt":"2026-01-01T00:00:00.000Z",
               "watchedThroughSeason":2,"inProgressSeason":3}
            ]}
            """.trimIndent()
        val result = parseExport(text) as ImportResult.Success
        assertEquals(3, result.shows.single().inProgressSeason)
    }

    @Test
    fun `drops an imported marker the watermark has already passed`() {
        // An import writes straight to the database, so a hand-edited or future-written
        // file is the one way a marker on a finished season could get stored.
        val text =
            """
            {"format":"showtracker-export","version":1,"shows":[
              {"id":7,"name":"Finished","addedAt":"2026-01-01T00:00:00.000Z",
               "watchedThroughSeason":3,"inProgressSeason":3}
            ]}
            """.trimIndent()
        val result = parseExport(text) as ImportResult.Success
        assertNull(result.shows.single().inProgressSeason)
    }

    @Test
    fun `refuses a file that is not an export`() {
        val result = parseExport("""{"format":"something-else","version":1,"shows":[]}""")
        assertTrue(result is ImportResult.Failure)
    }

    @Test
    fun `refuses a payload from a newer writer instead of dropping fields`() {
        val newer = sample().replace("\"version\": 1", "\"version\": 2")
        val result = parseExport(newer)
        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).reason.contains("newer"))
    }

    @Test
    fun `refuses malformed JSON rather than importing part of it`() {
        val truncated = sample().substring(0, sample().length / 2)
        assertTrue(parseExport(truncated) is ImportResult.Failure)
        assertTrue(parseExport("") is ImportResult.Failure)
        assertTrue(parseExport("not json at all") is ImportResult.Failure)
    }

    @Test
    fun `refuses an export with no shows`() {
        val empty = """{"format":"showtracker-export","version":1,"shows":[]}"""
        assertTrue(parseExport(empty) is ImportResult.Failure)
    }

    @Test
    fun `collapses duplicate ids rather than failing the whole import`() {
        val doubled =
            """
            {"format":"showtracker-export","version":1,"shows":[
              {"id":7,"name":"First","addedAt":"2026-01-01T00:00:00.000Z"},
              {"id":7,"name":"Second","addedAt":"2026-01-01T00:00:00.000Z"}
            ]}
            """.trimIndent()
        val result = parseExport(doubled) as ImportResult.Success
        assertEquals(1, result.shows.size)
        assertEquals("Second", result.shows.single().name)
    }
}
