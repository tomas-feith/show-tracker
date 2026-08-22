package com.showtracker.app.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exercises the TMDB wire mapping over a real socket. The mapping is where a rename or a
 * missed `@SerialName` would show up as a silently empty field rather than a failure, so it
 * is worth testing against actual bytes rather than by inspection.
 */
class TmdbClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TmdbClient

    private val v3Key = "0123456789abcdef0123456789abcdef"
    private val v4Token = "eyJhbGciOiJIUzI1NiJ9.payload.signature"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TmdbClient(baseUrl = server.url("/3").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(
        body: String,
        code: Int = 200,
    ) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private val detailJson =
        """
        {
          "id": 1396,
          "name": "Shōgun",
          "overview": "An overview.",
          "status": "Returning Series",
          "poster_path": "/poster.jpg",
          "first_air_date": "2024-02-27",
          "seasons": [
            {"season_number": 0, "name": "Specials", "air_date": "2024-03-01", "episode_count": 3},
            {"season_number": 1, "name": "Season 1", "air_date": "2024-02-27", "episode_count": 10},
            {"season_number": 2, "name": "Season 2", "air_date": "", "episode_count": 0}
          ],
          "last_episode_to_air": {
            "season_number": 1, "episode_number": 10, "name": "Finale", "air_date": "2024-04-23"
          },
          "next_episode_to_air": null,
          "an_unknown_field": 1
        }
        """.trimIndent()

    @Test
    fun `maps the snake_case detail payload onto the domain type`() =
        runTest {
            enqueue(detailJson)
            val show = client.fetchShow(v3Key, 1396)

            assertEquals(1396, show.id)
            assertEquals("Shōgun", show.name)
            assertEquals("Returning Series", show.status)
            assertEquals("/poster.jpg", show.posterPath)
            assertEquals("2024-02-27", show.firstAirDate)
            assertEquals(3, show.seasons.size)
            assertEquals(10, show.seasons[1].episodeCount)
            assertEquals(10, show.lastEpisode?.episodeNumber)
            assertNull(show.nextEpisode)
        }

    @Test
    fun `normalises an empty air date to null rather than an empty string`() =
        runTest {
            // TMDB returns "" for some missing dates. Storing that verbatim would make the
            // exported file differ from what the React Native build wrote.
            enqueue(detailJson)
            val show = client.fetchShow(v3Key, 1396)
            assertNull(show.seasons.single { it.seasonNumber == 2 }.airDate)
        }

    @Test
    fun `sends a v3 key as a query parameter and no auth header`() =
        runTest {
            enqueue(detailJson)
            client.fetchShow(v3Key, 1396)

            val request = server.takeRequest()
            assertEquals(v3Key, request.requestUrl?.queryParameter("api_key"))
            assertNull(request.getHeader("Authorization"))
        }

    @Test
    fun `sends a v4 token as a bearer header and never in the URL`() =
        runTest {
            // A token in the query string would end up in server logs and in any shared
            // URL; TMDB expects the JWT form as a header.
            enqueue(detailJson)
            client.fetchShow(v4Token, 1396)

            val request = server.takeRequest()
            assertEquals("Bearer $v4Token", request.getHeader("Authorization"))
            assertNull(request.requestUrl?.queryParameter("api_key"))
            assertTrue(request.path?.contains(v4Token) == false)
        }

    @Test
    fun `maps a rejected key to a message about the key`() =
        runTest {
            enqueue("""{"status_message":"Invalid API key"}""", code = 401)
            val error = runCatching { client.fetchShow(v3Key, 1) }.exceptionOrNull()
            assertTrue(error is TmdbException.Unauthorized)
            assertEquals("TMDB rejected your API key.", error?.message)
        }

    @Test
    fun `maps rate limiting to a message about waiting`() =
        runTest {
            enqueue("", code = 429)
            val error = runCatching { client.fetchShow(v3Key, 1) }.exceptionOrNull()
            assertTrue(error is TmdbException.RateLimited)
        }

    @Test
    fun `carries an unexpected status code through in the message`() =
        runTest {
            enqueue("", code = 503)
            val error = runCatching { client.fetchShow(v3Key, 1) }.exceptionOrNull()
            assertTrue(error is TmdbException.Http)
            assertEquals(503, (error as TmdbException.Http).status)
        }

    @Test
    fun `treats an unreadable 200 as malformed rather than crashing`() =
        runTest {
            enqueue("not json")
            val error = runCatching { client.fetchShow(v3Key, 1) }.exceptionOrNull()
            assertTrue(error is TmdbException.Malformed)
        }

    @Test
    fun `parses a search response`() =
        runTest {
            enqueue(
                """
                {"results":[
                  {"id":1,"name":"Glória","overview":"o","poster_path":null,"first_air_date":""}
                ]}
                """.trimIndent(),
            )
            val results = client.searchShows(v3Key, "  gloria  ")

            assertEquals(1, results.size)
            assertEquals("Glória", results.single().name)
            assertNull(results.single().posterPath)
            assertNull("an empty first_air_date is absent", results.single().firstAirDate)
            // The query is trimmed before it is sent.
            assertEquals("gloria", server.takeRequest().requestUrl?.queryParameter("query"))
        }

    @Test
    fun `does not call the network for a blank search`() =
        runTest {
            assertEquals(emptyList<Any>(), client.searchShows(v3Key, "   "))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `returns a per-show result so one failure cannot lose the rest`() =
        runTest {
            enqueue(detailJson)
            enqueue("", code = 503)
            enqueue(detailJson)

            val results = client.fetchShows(v3Key, listOf(1, 2, 3), concurrency = 1)

            assertEquals(setOf(1, 2, 3), results.keys)
            // With concurrency 1 the responses are consumed in order, so exactly one of the
            // three failed and the other two survived it.
            assertEquals(1, results.values.count { it.isFailure })
            assertEquals(2, results.values.count { it.isSuccess })
        }

    @Test
    fun `parses a recommendation list, votes included`() =
        runTest {
            enqueue(
                """
                {"results":[
                  {"id":7,"name":"Dark","poster_path":"/d.jpg","first_air_date":"2017-12-01",
                   "vote_average":8.4,"vote_count":4321}
                ]}
                """.trimIndent(),
            )
            val results = client.recommendationsFor(v3Key, 1396)

            assertEquals(1, results.size)
            // The votes are what the ranking's tiebreak reads, so a missed @SerialName here
            // would silently flatten every suggestion to the same score.
            assertEquals(8.4, results.single().voteAverage, 0.001)
            assertEquals(4321, results.single().voteCount)
            assertEquals("/3/tv/1396/recommendations", server.takeRequest().requestUrl?.encodedPath)
        }

    @Test
    fun `defaults the votes when TMDB omits them`() =
        runTest {
            enqueue("""{"results":[{"id":7,"name":"Dark"}]}""")
            val single = client.recommendationsFor(v3Key, 1).single()
            assertEquals(0.0, single.voteAverage, 0.001)
            assertEquals(0, single.voteCount)
        }

    @Test
    fun `drops adult titles from lists that cannot ask TMDB to filter them`() =
        runTest {
            // /trending and /tv/{id}/recommendations take no include_adult parameter, so
            // the policy /search/tv states in its request has to be applied to the response.
            val body =
                """
                {"results":[
                  {"id":1,"name":"Fine","adult":false},
                  {"id":2,"name":"Not fine","adult":true},
                  {"id":3,"name":"No adult field"}
                ]}
                """.trimIndent()

            enqueue(body)
            assertEquals(listOf(1, 3), client.trendingShows(v3Key).map { it.id })

            enqueue(body)
            assertEquals(listOf(1, 3), client.recommendationsFor(v3Key, 9).map { it.id })
        }

    @Test
    fun `asks for the weekly trending window by default`() =
        runTest {
            enqueue("""{"results":[{"id":7,"name":"Dark"}]}""")
            client.trendingShows(v3Key)
            assertEquals("/3/trending/tv/week", server.takeRequest().requestUrl?.encodedPath)
        }

    @Test
    fun `fetches recommendations for many shows, keeping one failure from losing the rest`() =
        runTest {
            enqueue("""{"results":[{"id":7,"name":"Dark"}]}""")
            enqueue("", code = 503)

            val results = client.fetchRecommendations(v3Key, listOf(1, 2), concurrency = 1)

            assertEquals(setOf(1, 2), results.keys)
            assertEquals(1, results.getValue(1).getOrNull()?.size)
            assertTrue(results.getValue(2).isFailure)
        }

    @Test
    fun `does not request the same id twice in one fan-out`() =
        runTest {
            enqueue(detailJson)
            val results = client.fetchShows(v3Key, listOf(1396, 1396), concurrency = 1)

            assertEquals(setOf(1396), results.keys)
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `fetches an empty id list without touching the network`() =
        runTest {
            assertEquals(emptyMap<Int, Any>(), client.fetchShows(v3Key, emptyList()))
            assertEquals(0, server.requestCount)
        }
}
