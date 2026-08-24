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
    fun `carries the in-progress marker, and its absence, across the entity boundary`() {
        // Null is a real value here - "nothing in progress" - not a missing one, so both
        // directions matter.
        assertNull(roundTrip(show).inProgressSeason)
        assertEquals(2, roundTrip(show.copy(inProgressSeason = 2)).inProgressSeason)
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
    fun `carries the show metadata across the entity boundary`() {
        val rich =
            show.copy(
                voteAverage = 8.4,
                voteCount = 4321,
                genres = listOf("Drama", "Action & Adventure"),
                episodeRunTime = 55,
                type = "Miniseries",
                numberOfEpisodes = 10,
            )
        val result = roundTrip(rich)

        assertEquals(8.4, result.voteAverage, 0.001)
        assertEquals(4321, result.voteCount)
        assertEquals(listOf("Drama", "Action & Adventure"), result.genres)
        assertEquals(55, result.episodeRunTime)
        assertEquals("Miniseries", result.type)
        assertEquals(10, result.numberOfEpisodes)
    }

    @Test
    fun `reads no genres back from an empty genre column`() {
        // `split` on "" yields one empty element rather than none, so without the guard a
        // show with no genres comes back holding a single blank tag.
        assertEquals("", show.copy(genres = emptyList()).toEntity().genres)
        assertEquals(emptyList<String>(), roundTrip(show.copy(genres = emptyList())).genres)
    }

    @Test
    fun `strips a separator that appears inside a genre name`() {
        // The one input the joined column cannot represent. TMDB has never sent such a
        // name - it is a control character - but silently splitting one tag into two that
        // do not exist is a worse failure than dropping a character nothing can render.
        val awkward = listOf("Dra\u001Fma", "Comedy")
        assertEquals(listOf("Drama", "Comedy"), roundTrip(show.copy(genres = awkward)).genres)
    }

    @Test
    fun `keeps a single genre whole rather than splitting it`() {
        // Every TMDB TV genre containing punctuation - the separator must survive them.
        val awkward = listOf("Action & Adventure", "Sci-Fi & Fantasy", "War & Politics")
        assertEquals(awkward, roundTrip(show.copy(genres = awkward)).genres)
    }

    @Test
    fun `keeps an unknown episode length null rather than storing a zero`() {
        // 0 would render as "0m episodes", which is a claim; null renders as nothing.
        assertNull(roundTrip(show.copy(episodeRunTime = null)).episodeRunTime)
        assertEquals(42, roundTrip(show.copy(episodeRunTime = 42)).episodeRunTime)
    }

    @Test
    fun `maps an empty season list to no rows at all`() {
        val empty = show.copy(seasons = emptyList())
        assertEquals(emptyList<SeasonEntity>(), empty.toSeasonEntities())
        assertEquals(emptyList<Season>(), roundTrip(empty).seasons)
    }
}
