package com.showtracker.app.data

import com.showtracker.app.domain.EpisodeRef
import com.showtracker.app.domain.Season
import com.showtracker.app.domain.TrackedShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The export half of the transfer format. The round trip through [parseExport] is the test
 * that matters: after the old app is gone this is the only backup path, so a file this app
 * writes and cannot read again would be a backup that silently is not one.
 */
class LibraryExportTest {
    private val now: Instant = Instant.parse("2026-08-15T18:45:12Z")

    private val shows =
        listOf(
            TrackedShow(
                id = 1,
                name = "Shōgun",
                posterPath = "/poster.jpg",
                firstAirDate = "2024-02-27",
                status = "Returning Series",
                seasons =
                    listOf(
                        Season(0, "Specials", "2024-03-01", 3),
                        Season(1, "Season 1", "2024-02-27", 10),
                        Season(2, "Season 2", null, 0),
                    ),
                lastEpisode = EpisodeRef(1, 10, "Finale", "2024-04-23"),
                nextEpisode = null,
                watchedThroughSeason = 0,
                knownAiredSeason = 1,
                addedAt = "2026-01-05T10:00:00Z",
                lastCheckedAt = "2026-08-14T09:30:00Z",
            ),
            TrackedShow(
                id = 2,
                name = "Glória",
                posterPath = null,
                firstAirDate = null,
                status = "Ended",
                seasons = listOf(Season(1, "Season 1", "2021-11-05", 10)),
                watchedThroughSeason = 1,
                knownAiredSeason = 1,
                addedAt = "2026-02-01T00:00:00Z",
                lastCheckedAt = null,
            ),
        )

    @Test
    fun `round trips every show through its own importer`() {
        val text = buildExport(shows, "2026-08-15T14:42:15Z", now)
        val result = parseExport(text) as ImportResult.Success

        assertEquals(shows, result.shows)
        assertEquals("2026-08-15T14:42:15Z", result.lastCheckedAt)
    }

    @Test
    fun `stamps the format and version the reader checks`() {
        val text = buildExport(shows, null, now)
        assertTrue(text.contains("\"format\": \"$EXPORT_FORMAT\""))
        assertTrue(text.contains("\"version\": $EXPORT_VERSION"))
        assertTrue(text.contains("\"exportedAt\": \"2026-08-15T18:45:12Z\""))
    }

    @Test
    fun `never writes the TMDB key`() {
        val text = buildExport(shows, null, now)
        assertTrue(!text.contains("apiKey", ignoreCase = true))
        assertTrue(!text.contains("tmdb", ignoreCase = true))
    }

    @Test
    fun `never writes the obsolete single watermark`() {
        // The reader still accepts acknowledgedSeason so an old file imports. Nothing
        // should be producing it again.
        assertTrue(!buildExport(shows, null, now).contains("acknowledgedSeason"))
    }

    @Test
    fun `preserves a deliberate zero watermark through the round trip`() {
        val restored = parseExport(buildExport(shows, null, now)) as ImportResult.Success
        val shogun = restored.shows.single { it.id == 1 }
        assertEquals(0, shogun.watchedThroughSeason)
        assertEquals(1, shogun.knownAiredSeason)
    }

    @Test
    fun `preserves nulls and non-ASCII names`() {
        val restored = parseExport(buildExport(shows, null, now)) as ImportResult.Success
        val gloria = restored.shows.single { it.id == 2 }
        assertEquals("Glória", gloria.name)
        assertEquals(null, gloria.posterPath)
        assertEquals(null, gloria.lastCheckedAt)
        assertTrue(restored.shows.any { s -> s.name.any { it.code == 0x14D } })
    }

    @Test
    fun `keeps season 0 and undated placeholder seasons`() {
        val restored = parseExport(buildExport(shows, null, now)) as ImportResult.Success
        val seasons = restored.shows.single { it.id == 1 }.seasons
        assertEquals(3, seasons.size)
        assertEquals(Season(2, "Season 2", null, 0), seasons.last())
    }

    @Test
    fun `names the file by local calendar date`() {
        assertEquals(
            "show-tracker-2026-08-15.json",
            exportFileName(LocalDate.parse("2026-08-15")),
        )
    }

    @Test
    fun `writes an empty library without failing, even though it will not import`() {
        // Exporting nothing is legal; importing nothing is refused, because an empty file
        // is far more likely to be a mistake than an intention.
        val text = buildExport(emptyList(), null, now)
        assertTrue(text.contains("\"shows\""))
        assertTrue(parseExport(text) is ImportResult.Failure)
    }
}
