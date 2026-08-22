package com.showtracker.app.network

import com.showtracker.app.domain.EpisodeRef
import com.showtracker.app.domain.SearchResult
import com.showtracker.app.domain.Season
import com.showtracker.app.domain.ShowDetail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val BASE = "https://api.themoviedb.org/3"
private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_RATE_LIMITED = 429

/** Raised for any non-OK TMDB response. [message] is written to be shown to the user. */
sealed class TmdbException(
    message: String,
) : Exception(message) {
    class Unauthorized : TmdbException("TMDB rejected your API key.")

    class RateLimited : TmdbException("Rate limited by TMDB. Try again shortly.")

    class Offline : TmdbException("Could not reach TMDB. Check your connection.")

    class Http(
        val status: Int,
    ) : TmdbException("TMDB request failed ($status).")

    /** A 200 whose body was not the shape we expected. */
    class Malformed : TmdbException("TMDB returned something unreadable.")
}

/** Poster URL at a sensible width, or null when the show has no artwork. */
fun posterUrl(
    path: String?,
    size: String = "w342",
): String? = path?.let { "$IMAGE_BASE/$size$it" }

/**
 * TMDB accepts either a v3 API key (a 32-character hex string, passed as a query
 * parameter) or a v4 read access token (a JWT, passed as a bearer header). Users copy
 * whichever one their dashboard shows them, so both are accepted.
 */
private fun isV4Token(key: String): Boolean = key.startsWith("eyJ")

/**
 * TMDB returns `""` rather than null for missing dates in some fields. Normalising to null
 * here keeps the stored and exported value identical to what the React Native build wrote,
 * and the domain already treats null as "not scheduled".
 */
private fun cleanDate(value: String?): String? = value?.takeIf { it.isNotEmpty() }

class TmdbClient(
    private val http: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    /** Overridden only by tests, which point it at a local server. */
    private val baseUrl: String = BASE,
) {
    companion object {
        /** How many shows a library refresh fetches at once. */
        const val DEFAULT_CONCURRENCY = 5

        /** The `/trending` time window. TMDB also accepts "day"; see [trendingShows]. */
        const val TRENDING_WEEK = "week"

        private const val CONNECT_TIMEOUT_SECONDS = 10L

        /**
         * Longer than the connect timeout: a background refresh on mobile data is worth
         * waiting on, whereas a host that will not connect at all is not.
         */
        private const val READ_TIMEOUT_SECONDS = 20L

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
    }

    private suspend inline fun <reified T> request(
        key: String,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): T {
        val url =
            (baseUrl + path)
                .toHttpUrl()
                .newBuilder()
                .apply {
                    params.forEach { (k, v) -> addQueryParameter(k, v) }
                    if (!isV4Token(key)) addQueryParameter("api_key", key)
                }.build()

        val body = get(url, key)
        return runCatching { json.decodeFromString<T>(body) }
            .getOrElse { throw TmdbException.Malformed() }
    }

    private suspend fun get(
        url: HttpUrl,
        key: String,
    ): String =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("accept", "application/json")
                    .apply { if (isV4Token(key)) header("Authorization", "Bearer $key") }
                    .build()

            val response =
                try {
                    http.newCall(request).execute()
                } catch (e: IOException) {
                    // Connectivity, DNS and timeouts all land here, and none of them is
                    // distinguishable to a user standing on a train.
                    throw TmdbException.Offline().initCause(e)
                }

            response.use {
                when {
                    it.isSuccessful -> it.body?.string() ?: throw TmdbException.Malformed()
                    it.code == HTTP_UNAUTHORIZED -> throw TmdbException.Unauthorized()
                    it.code == HTTP_RATE_LIMITED -> throw TmdbException.RateLimited()
                    else -> throw TmdbException.Http(it.code)
                }
            }
        }

    /** Search TV shows by name, most relevant first. */
    suspend fun searchShows(
        key: String,
        query: String,
    ): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val data =
            request<SearchResponse>(
                key,
                "/search/tv",
                mapOf("query" to trimmed, "include_adult" to "false"),
            )

        return data.results.dropAdult()
    }

    /**
     * TMDB's own recommendations for one show.
     *
     * The first page only. It is ordered by TMDB's confidence, so page two is the tail of a
     * list whose head we are already about to re-rank against every other show in the
     * library - and each extra page is another request per library show.
     */
    suspend fun recommendationsFor(
        key: String,
        id: Int,
    ): List<SearchResult> =
        request<SearchResponse>(key, "/tv/$id/recommendations").results.dropAdult()

    /**
     * What is trending on TMDB right now, across everyone - no library involved.
     *
     * [window] is `day` or `week`; week is steadier, a day's list swings on a single
     * premiere.
     */
    suspend fun trendingShows(
        key: String,
        window: String = TRENDING_WEEK,
    ): List<SearchResult> = request<SearchResponse>(key, "/trending/tv/$window").results.dropAdult()

    /** Fetch full detail for one show, including its season list. */
    suspend fun fetchShow(
        key: String,
        id: Int,
    ): ShowDetail = request<DetailResponse>(key, "/tv/$id").toDomain()

    /** Cheap call used to validate a key the user just pasted. */
    suspend fun verifyKey(key: String) {
        request<ConfigurationResponse>(key, "/configuration")
    }

    /**
     * Fetch many shows with bounded concurrency.
     *
     * TMDB tolerates far more, but a phone on mobile data does not, and refreshing a large
     * library should not open eighty sockets at once. Each result is captured separately so
     * one failure cannot abandon the rest: the caller keeps the previous data for whatever
     * did not come back.
     */
    suspend fun fetchShows(
        key: String,
        ids: List<Int>,
        concurrency: Int = DEFAULT_CONCURRENCY,
    ): Map<Int, Result<ShowDetail>> = fanOut(ids, concurrency) { fetchShow(key, it) }

    /**
     * Recommendations for many shows at once, on the same terms as [fetchShows]: bounded
     * concurrency, and one failure captured rather than thrown, because a suggestion list
     * built from nine of ten libraries shows is still worth showing.
     */
    suspend fun fetchRecommendations(
        key: String,
        ids: List<Int>,
        concurrency: Int = DEFAULT_CONCURRENCY,
    ): Map<Int, Result<List<SearchResult>>> =
        fanOut(ids, concurrency) { recommendationsFor(key, it) }

    /** The shared bounded fan-out. See [fetchShows] for why it is shaped this way. */
    private suspend fun <T> fanOut(
        ids: List<Int>,
        concurrency: Int,
        fetch: suspend (Int) -> T,
    ): Map<Int, Result<T>> =
        coroutineScope {
            val gate = Semaphore(concurrency.coerceAtLeast(1))
            ids
                .distinct()
                .map { id ->
                    async {
                        id to gate.withPermit { runCatching { fetch(id) } }
                    }
                }.awaitAll()
                .toMap()
        }
}

// --- wire types ---

@Serializable
private data class SearchResponse(
    val results: List<SearchItem> = emptyList(),
)

/**
 * Drop adult titles, then map to the domain.
 *
 * `/search/tv` is told `include_adult=false` in the request, but `/trending` and
 * `/tv/{id}/recommendations` accept no such parameter, so for those the same policy can
 * only be applied to the response. Doing it for all three keeps one rule rather than two.
 */
private fun List<SearchItem>.dropAdult(): List<SearchResult> =
    filterNot { it.adult }.map { it.toDomain() }

@Serializable
private data class SearchItem(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    /** Absent on some TV payloads, in which case the title is not adult. */
    val adult: Boolean = false,
) {
    fun toDomain(): SearchResult =
        SearchResult(
            id = id,
            name = name,
            overview = overview,
            posterPath = posterPath,
            firstAirDate = cleanDate(firstAirDate),
            voteAverage = voteAverage,
            voteCount = voteCount,
        )
}

@Serializable
private data class ConfigurationResponse(
    val images: JsonImages? = null,
) {
    @Serializable
    data class JsonImages(
        @SerialName("secure_base_url") val secureBaseUrl: String? = null,
    )
}

@Serializable
private data class DetailResponse(
    val id: Int,
    val name: String = "",
    val overview: String = "",
    val status: String = "",
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val seasons: List<RawSeason> = emptyList(),
    @SerialName("last_episode_to_air") val lastEpisode: RawEpisode? = null,
    @SerialName("next_episode_to_air") val nextEpisode: RawEpisode? = null,
) {
    fun toDomain(): ShowDetail =
        ShowDetail(
            id = id,
            name = name,
            overview = overview,
            posterPath = posterPath,
            firstAirDate = cleanDate(firstAirDate),
            status = status,
            seasons = seasons.map { it.toDomain() },
            lastEpisode = lastEpisode?.toDomain(),
            nextEpisode = nextEpisode?.toDomain(),
        )
}

@Serializable
private data class RawSeason(
    @SerialName("season_number") val seasonNumber: Int,
    val name: String = "",
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
) {
    fun toDomain(): Season = Season(seasonNumber, name, cleanDate(airDate), episodeCount)
}

@Serializable
private data class RawEpisode(
    @SerialName("season_number") val seasonNumber: Int,
    @SerialName("episode_number") val episodeNumber: Int,
    val name: String = "",
    @SerialName("air_date") val airDate: String? = null,
) {
    fun toDomain(): EpisodeRef = EpisodeRef(seasonNumber, episodeNumber, name, cleanDate(airDate))
}
