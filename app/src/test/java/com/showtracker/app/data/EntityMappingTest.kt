package com.showtracker.app.data

import com.showtracker.app.domain.EpisodeRef
import com.showtracker.app.domain.Season
import com.showtracker.app.domain.TrackedShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Room entities are plain data classes, so the mapping either side of them is testable
 * without a device. This is the seam an import passes through on its way to disk, and a
 * field dropped here would be silent: the show would still be there, just wrong.
 */
class EntityMappingTest {
    private val show =
        TrackedShow(
            id = 1396,
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
            lastEpisode = EpisodeRef(1, 10, "A Dream of a Dream", "2024-04-23"),
            nextEpisode = null,
            watchedThroughSeason = 0,
            knownAiredSeason = 1,
            addedAt = "2026-01-05T10:00:00.000Z",
            lastCheckedAt = "2026-08-14T09:30:00.000Z",
        )

    private fun roundTrip(source: TrackedShow): TrackedShow =
        ShowWithSeasons(source.toEntity(), source.toSeasonEntities()).toDomain()

    @Test
    fun `survives a full round trip through the entity types`() {
        assertEquals(show, roundTrip(show))
    }

    @Test
    fun `keeps a null next episode null rather than materialising an empty one`() {
        // The case that made these columns explicitly nullable: 89% of a real library has
        // no next episode, and a nullable @Embedded would treat "all columns null" and
        // "absent" as the same thing.
        assertNull(roundTrip(show).nextEpisode)
        assertEquals(
            EpisodeRef(1, 10, "A Dream of a Dream", "2024-04-23"),
            roundTrip(show).lastEpisode,
        )
    }

    @Test
    fun `keeps both episode markers null when neither exists`() {
        val bare = show.copy(lastEpisode = null, nextEpisode = null)
        assertNull(roundTrip(bare).lastEpisode)
        assertNull(roundTrip(bare).nextEpisode)
    }

    @Test
    fun `preserves a deliberate zero watermark`() {
        // "Not started" must not be confused with "no value".
        assertEquals(0, roundTrip(show).watchedThroughSeason)
        assertEquals(1, roundTrip(show).knownAiredSeason)
    }

    @Test
    fun `preserves nullable metadata that is never null in the sample library`() {
        // 0% of the real export has a null poster or first air date, which says nothing
        // about whether one can occur. TMDB returns null for a show with no artwork.
        val sparse = show.copy(posterPath = null, firstAirDate = null, lastCheckedAt = null)
        val result = roundTrip(sparse)
        assertNull(result.posterPath)
        assertNull(result.firstAirDate)
        assertNull(result.lastCheckedAt)
    }

    @Test
    fun `carries non-ASCII titles through unchanged`() {
        assertEquals("Shōgun", roundTrip(show).name)
        assertEquals("Glória", roundTrip(show.copy(name = "Glória")).name)
    }

    @Test
    fun `keeps season 0 and placeholder seasons rather than filtering on write`() {
        val seasons = roundTrip(show).seasons
        assertEquals(3, seasons.size)
        assertEquals(0, seasons.first().seasonNumber)
        // An announced season with no date and no episodes still round trips.
        assertEquals(Season(2, "Season 2", null, 0), seasons.last())
    }

    @Test
    fun `orders seasons by number regardless of row order`() {
        val shuffled =
            ShowWithSeasons(show.toEntity(), show.toSeasonEntities().reversed()).toDomain()
        assertEquals(listOf(0, 1, 2), shuffled.seasons.map { it.seasonNumber })
    }

    @Test
    fun `keys each season row to its show`() {
        val rows = show.toSeasonEntities()
        assertEquals(3, rows.size)
        assertEquals(setOf(1396), rows.map { it.showId }.toSet())
        // (showId, seasonNumber) is the primary key, so the pairs must be unique.
        assertEquals(rows.size, rows.map { it.showId to it.seasonNumber }.toSet().size)
    }

    @Test
    fun `maps an empty season list to no rows at all`() {
        val empty = show.copy(seasons = emptyList())
        assertEquals(emptyList<SeasonEntity>(), empty.toSeasonEntities())
        assertEquals(emptyList<Season>(), roundTrip(empty).seasons)
    }
}
