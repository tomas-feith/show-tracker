package com.showtracker.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.showtracker.app.domain.EpisodeRef
import com.showtracker.app.domain.Season
import com.showtracker.app.domain.TrackedShow

/**
 * Dates stay as `YYYY-MM-DD` strings rather than becoming epoch days.
 *
 * habit_tracker stores dates as epoch days so SQL can compare and range over them, which
 * is right there because its queries filter by date. Nothing here does: a show is always
 * loaded whole and every date decision happens in [com.showtracker.app.domain] against
 * today's calendar. Keeping the strings means the row, the TMDB response and the export
 * file all carry the identical value, so nothing can be lost or reinterpreted in between.
 */
@Entity(tableName = "shows")
data class ShowEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val posterPath: String?,
    val firstAirDate: String?,
    val status: String,
    @Embedded(prefix = "last_") val lastEpisode: EpisodeColumns = EpisodeColumns(),
    @Embedded(prefix = "next_") val nextEpisode: EpisodeColumns = EpisodeColumns(),
    val watchedThroughSeason: Int,
    /** Null when no season is in progress. Added at schema version 2. */
    val inProgressSeason: Int?,
    val knownAiredSeason: Int,
    val addedAt: String,
    val lastCheckedAt: String?,
)

/**
 * The columns of an episode marker, all nullable.
 *
 * Deliberately embedded non-null with nullable columns rather than as a nullable
 * `@Embedded`. Room decides a nullable embedded is absent only when every one of its
 * columns is null, which makes "no next episode" and "an episode whose fields happen to be
 * null" the same state. Being explicit here removes the ambiguity: [toDomain] returns null
 * unless the identifying fields are both present. It matters - `nextEpisode` is null for
 * 89% of a real library.
 */
data class EpisodeColumns(
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val name: String? = null,
    val airDate: String? = null,
) {
    fun toDomain(): EpisodeRef? {
        val season = seasonNumber ?: return null
        val episode = episodeNumber ?: return null
        return EpisodeRef(season, episode, name.orEmpty(), airDate)
    }
}

fun EpisodeRef?.toColumns(): EpisodeColumns =
    if (this == null) {
        EpisodeColumns()
    } else {
        EpisodeColumns(seasonNumber, episodeNumber, name, airDate)
    }

/**
 * A season, as its own row rather than a JSON blob on the show.
 *
 * A blob column would have reintroduced the unversioned-nested-shape problem that leaving
 * AsyncStorage was meant to solve: the outer table would be migratable and the seasons
 * inside it would not. A real library is 262 seasons across 80 shows, up to 19 on one, so
 * the table stays small either way.
 *
 * The primary key is (showId, seasonNumber) because that pair is what identifies a season;
 * a surrogate id would allow the same season twice.
 */
@Entity(
    tableName = "seasons",
    primaryKeys = ["showId", "seasonNumber"],
    foreignKeys = [
        ForeignKey(
            entity = ShowEntity::class,
            parentColumns = ["id"],
            childColumns = ["showId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("showId")],
)
data class SeasonEntity(
    val showId: Int,
    val seasonNumber: Int,
    val name: String,
    val airDate: String?,
    val episodeCount: Int,
)

/** A show and its seasons, which is the only way a show is ever read. */
data class ShowWithSeasons(
    @Embedded val show: ShowEntity,
    @Relation(parentColumn = "id", entityColumn = "showId")
    val seasons: List<SeasonEntity>,
)

fun ShowWithSeasons.toDomain(): TrackedShow =
    TrackedShow(
        id = show.id,
        name = show.name,
        posterPath = show.posterPath,
        firstAirDate = show.firstAirDate,
        status = show.status,
        // Ordered here rather than in SQL so every read is consistent regardless of which
        // query produced it. The domain never assumes an order, but a stable one makes
        // equality checks and test failures readable.
        seasons =
            seasons
                .sortedBy { it.seasonNumber }
                .map { Season(it.seasonNumber, it.name, it.airDate, it.episodeCount) },
        lastEpisode = show.lastEpisode.toDomain(),
        nextEpisode = show.nextEpisode.toDomain(),
        watchedThroughSeason = show.watchedThroughSeason,
        inProgressSeason = show.inProgressSeason,
        knownAiredSeason = show.knownAiredSeason,
        addedAt = show.addedAt,
        lastCheckedAt = show.lastCheckedAt,
    )

fun TrackedShow.toEntity(): ShowEntity =
    ShowEntity(
        id = id,
        name = name,
        posterPath = posterPath,
        firstAirDate = firstAirDate,
        status = status,
        lastEpisode = lastEpisode.toColumns(),
        nextEpisode = nextEpisode.toColumns(),
        watchedThroughSeason = watchedThroughSeason,
        inProgressSeason = inProgressSeason,
        knownAiredSeason = knownAiredSeason,
        addedAt = addedAt,
        lastCheckedAt = lastCheckedAt,
    )

fun TrackedShow.toSeasonEntities(): List<SeasonEntity> =
    seasons.map { SeasonEntity(id, it.seasonNumber, it.name, it.airDate, it.episodeCount) }
