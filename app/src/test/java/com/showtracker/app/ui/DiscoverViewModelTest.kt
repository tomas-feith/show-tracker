package com.showtracker.app.ui

import com.showtracker.app.data.ApiKeySource
import com.showtracker.app.data.DiscoverLibrary
import com.showtracker.app.domain.TrackedShow
import com.showtracker.app.network.TmdbClient
import com.showtracker.app.ui.discover.DiscoverTab
import com.showtracker.app.ui.discover.DiscoverViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The discovery screen's logic, over a real [TmdbClient] pointed at a local server.
 *
 * Both bugs pinned here shipped once: a cancelled load left its tab spinning for ever, and
 * a library whose every seed failed rendered as "follow a few shows and..." over a full
 * library. Neither is visible from the domain layer, and neither had a test, because the
 * ViewModel could not be constructed off a device until [ApiKeySource] and
 * [DiscoverLibrary] existed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var tmdb: TmdbClient
    private val dispatcher = StandardTestDispatcher()

    private val key = "0123456789abcdef0123456789abcdef"

    private class FakeKey(
        value: String?,
    ) : ApiKeySource {
        override val apiKey: Flow<String?> = flowOf(value)
    }

    private class FakeLibrary(
        private val shows: List<TrackedShow>,
        private val alreadyDismissed: Set<Int> = emptySet(),
    ) : DiscoverLibrary {
        val dismissals = mutableListOf<Pair<Int, String>>()

        override suspend fun all(): List<TrackedShow> = shows

        override suspend fun dismissedIds(): Set<Int> = alreadyDismissed

        override suspend fun dismiss(
            id: Int,
            name: String,
            at: String,
        ) {
            dismissals += id to name
        }
    }

    /** Pulls the show id out of `/3/tv/{id}/recommendations`. */
    private val seedIdPattern = Regex("""/tv/(\d+)/recommendations""")

    private fun show(
        id: Int,
        name: String = "Show $id",
        favourite: Boolean = false,
    ) = TrackedShow(id = id, name = name, addedAt = "2026-01-0$id", favourite = favourite)

    private fun recommendations(vararg ids: Int): String =
        ids.joinToString(
            prefix = """{"results":[""",
            postfix = "]}",
            separator = ",",
        ) { """{"id":$it,"name":"Rec $it","vote_average":8.0,"vote_count":900}""" }

    private fun viewModel(library: DiscoverLibrary) = DiscoverViewModel(tmdb, FakeKey(key), library)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        server = MockWebServer()
        server.start()
        tmdb =
            TmdbClient(
                baseUrl = server.url("/3").toString().trimEnd('/'),
                io = dispatcher,
            )
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `every seed failing is reported as an error, not as an empty library`() =
        runTest(dispatcher) {
            // The whole library is present; it is the network that is broken. Rendering the
            // "follow a few shows" empty state here told the user the opposite of the truth.
            repeat(2) { server.enqueue(MockResponse().setResponseCode(503)) }

            val model = viewModel(FakeLibrary(listOf(show(1), show(2))))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            val tab = model.state.value.forYou
            assertNotNull("a total failure must surface as an error", tab.error)
            assertTrue(tab.items.isEmpty())
            // Not marked loaded, so returning to the tab retries rather than showing blank.
            assertFalse(tab.loaded)
            assertFalse(tab.loading)
        }

    @Test
    fun `an empty library is an empty result rather than an error`() =
        runTest(dispatcher) {
            val model = viewModel(FakeLibrary(emptyList()))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            val tab = model.state.value.forYou
            assertNull(tab.error)
            assertTrue(tab.loaded)
            assertTrue(tab.items.isEmpty())
            assertEquals("no seeds means no requests", 0, server.requestCount)
        }

    @Test
    fun `switching tabs mid-load does not leave the abandoned tab spinning`() =
        runTest(dispatcher) {
            // The abandoned load unwinds with a CancellationException, which
            // catchingUserFacing rethrows - so its failure branch never runs, and only the
            // `finally` can clear the flag. Left stuck, `load` also refuses to start the
            // tab again, so the spinner was permanent.
            //
            // Routed by path rather than queued: a cancelled load consumes no response, so
            // a queue would hand the trending request whatever the abandoned one left
            // behind and the test would be asserting on response order, not on behaviour.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path.orEmpty().contains("/trending/")) {
                            MockResponse().setBody("""{"results":[{"id":99,"name":"T"}]}""")
                        } else {
                            MockResponse().setBody(recommendations(10, 11))
                        }
                }

            val model = viewModel(FakeLibrary(listOf(show(1))))
            model.load(DiscoverTab.FOR_YOU)
            model.selectTab(DiscoverTab.TRENDING)
            advanceUntilIdle()

            assertFalse(
                "the abandoned tab must not stay loading",
                model.state.value.forYou.loading,
            )

            // And it must still be startable afterwards.
            model.selectTab(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            assertTrue(model.state.value.forYou.loaded)
            assertEquals(
                listOf(10, 11),
                model.state.value.forYou.items
                    .map { it.show.id },
            )
        }

    @Test
    fun `dismissing a suggestion removes it and records the name`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setBody(recommendations(10, 11, 12)))

            val library = FakeLibrary(listOf(show(1)))
            val model = viewModel(library)
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            model.dismiss(11)
            advanceUntilIdle()

            assertEquals(
                listOf(10, 12),
                model.state.value.forYou.items
                    .map { it.show.id },
            )
            // The name travels with the id, so the un-hide list can be read by a human.
            assertEquals(listOf(11 to "Rec 11"), library.dismissals)
        }

    @Test
    fun `already dismissed shows never enter the suggestions`() =
        runTest(dispatcher) {
            server.enqueue(MockResponse().setBody(recommendations(10, 11, 12)))

            val model = viewModel(FakeLibrary(listOf(show(1)), alreadyDismissed = setOf(11)))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            assertEquals(
                listOf(10, 12),
                model.state.value.forYou.items
                    .map { it.show.id },
            )
        }

    @Test
    fun `show more pages through the pool without asking TMDB again`() =
        runTest(dispatcher) {
            // Two pages' worth, so there is somewhere to go.
            val ids = (100 until 100 + 45).toList()
            server.enqueue(MockResponse().setBody(recommendations(*ids.toIntArray())))

            val model = viewModel(FakeLibrary(listOf(show(1))))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            val firstPage =
                model.state.value.forYou.items
                    .map { it.show.id }
            assertEquals(30, firstPage.size)
            assertTrue(model.state.value.moreSuggestions)

            val requestsBefore = server.requestCount
            model.showMore(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            val secondPage =
                model.state.value.forYou.items
                    .map { it.show.id }
            assertEquals("the second page is the rest of the pool", 15, secondPage.size)
            assertTrue("pages must not overlap", secondPage.none { it in firstPage })
            assertEquals("paging costs no network", requestsBefore, server.requestCount)
            assertFalse(model.state.value.moreSuggestions)
        }

    @Test
    fun `show more does nothing once the pool is spent`() =
        runTest(dispatcher) {
            // A single page's worth: there is no second page to walk to, and the button
            // that would ask for one is disabled by the same flag asserted here.
            server.enqueue(MockResponse().setBody(recommendations(10, 11)))

            val model = viewModel(FakeLibrary(listOf(show(1))))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            val requestsBefore = server.requestCount
            assertFalse(model.state.value.moreSuggestions)
            model.showMore(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            assertEquals("show more never asks TMDB", requestsBefore, server.requestCount)
            assertEquals(
                listOf(10, 11),
                model.state.value.forYou.items
                    .map { it.show.id },
            )
        }

    @Test
    fun `refreshing re-asks TMDB and starts again from the top`() =
        runTest(dispatcher) {
            // The ranking is a pure function of the seeds and their lists, so refreshing
            // an unchanged library must give back the list it gave before - including
            // after paging away from it. A refresh that resumed mid-pool would look like
            // the re-rolled list the ranking exists to avoid.
            val ids = (100 until 100 + 45).toList()
            val body = recommendations(*ids.toIntArray())
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setBody(body)
                }

            val model = viewModel(FakeLibrary(listOf(show(1))))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            val firstPage =
                model.state.value.forYou.items
                    .map { it.show.id }

            model.showMore(DiscoverTab.FOR_YOU)
            advanceUntilIdle()
            assertNotEquals(
                firstPage,
                model.state.value.forYou.items
                    .map { it.show.id },
            )

            val requestsBefore = server.requestCount
            model.refresh(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            assertEquals("refresh asks again", requestsBefore + 1, server.requestCount)
            assertEquals(
                "and lands back on the same first page",
                firstPage,
                model.state.value.forYou.items
                    .map { it.show.id },
            )
            assertTrue(model.state.value.moreSuggestions)
        }

    @Test
    fun `refreshing for you moves the seed window on, and wraps`() =
        runTest(dispatcher) {
            // A library half again as big as the cap, so the windows overlap on the wrap
            // rather than dividing evenly - which is the case that would hide an off-by-one.
            val size = DiscoverViewModel.MAX_SEEDS + 20
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setBody(recommendations(9001, 9002))
                }

            // addedAt descending is the seed order, so show 1 is newest and seeds first.
            val library =
                FakeLibrary(
                    (1..size).map { n ->
                        TrackedShow(
                            id = 100 + n,
                            name = "Show $n",
                            addedAt = "2026-01-01T00:00:%02dZ".format(size - n),
                        )
                    },
                )
            val model = viewModel(library)

            // Drains only what has arrived since the last call: requestCount is cumulative,
            // and asking for all of it twice takes more requests than the queue holds. The
            // timeout is the belt - an untimed takeRequest on an empty queue blocks the
            // test thread for ever, where a wrong count should merely fail.
            var drained = 0

            fun seedsAsked(): Set<Int> {
                val asked = mutableSetOf<Int>()
                while (drained < server.requestCount) {
                    val request = server.takeRequest(1, TimeUnit.SECONDS) ?: break
                    drained += 1
                    seedIdPattern
                        .find(request.path.orEmpty())
                        ?.let { asked += it.groupValues[1].toInt() }
                }
                return asked
            }

            // Ids 101..160, newest first, so a cold start seeds from 101.
            val newest = 101
            val oldest = 100 + size

            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()
            val first = seedsAsked()
            assertEquals("a full window every time", DiscoverViewModel.MAX_SEEDS, first.size)
            assertEquals("a cold start seeds from the newest", (101..140).toSet(), first)

            model.refresh(DiscoverTab.FOR_YOU)
            advanceUntilIdle()
            val second = seedsAsked()
            assertEquals(DiscoverViewModel.MAX_SEEDS, second.size)
            assertTrue("the window has moved on", second != first)
            // Everything the first window could not reach is asked about now, and the
            // window wraps to fill itself rather than coming up short at the end.
            assertTrue((141..oldest).all { it in second })
            assertTrue("the wrap reaches back round to the newest", newest in second)

            model.refresh(DiscoverTab.FOR_YOU)
            advanceUntilIdle()
            val third = seedsAsked()
            assertEquals(DiscoverViewModel.MAX_SEEDS, third.size)
            assertEquals("and carries on from where the wrap left off", (121..160).toSet(), third)

            // Three windows of forty over sixty shows: every show has now seeded at least
            // once, which is the point of rotating rather than sampling.
            assertEquals((newest..oldest).toSet(), first + second + third)
        }

    @Test
    fun `a library no bigger than the cap seeds from all of it every time`() =
        runTest(dispatcher) {
            // Nothing to rotate to, so refreshing stays the deterministic re-ask it was.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setBody(recommendations(9001, 9002))
                }

            val model = viewModel(FakeLibrary(listOf(show(1), show(2), show(3))))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()
            val first =
                model.state.value.forYou.items
                    .map { it.show.id }

            model.refresh(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            assertEquals(
                first,
                model.state.value.forYou.items
                    .map { it.show.id },
            )
        }

    @Test
    fun `the favourites tab is seeded by the starred shows alone`() =
        runTest(dispatcher) {
            // One response, because only the starred show may be asked about. A second
            // request would consume nothing and the assertion on the count would catch it.
            server.enqueue(MockResponse().setBody(recommendations(10, 11)))

            val library =
                FakeLibrary(listOf(show(1), show(2, favourite = true), show(3)))
            val model = viewModel(library)
            model.load(DiscoverTab.FAVOURITES)
            advanceUntilIdle()

            val tab = model.state.value.favourites
            assertNull(tab.error)
            assertTrue(tab.loaded)
            assertEquals(listOf(10, 11), tab.items.map { it.show.id })
            assertEquals("one seed, one request", 1, server.requestCount)
            // "Because you follow Show 2" - the reason must name the starred show, since
            // that is the whole claim the tab makes.
            assertEquals(listOf("Show 2"), tab.items.first().becauseOf)

            // And the tab it is not is untouched: the two hold separate pools.
            assertTrue(
                model.state.value.forYou.items
                    .isEmpty(),
            )
        }

    @Test
    fun `every starred show seeds the favourites tab, past the for-you cap`() =
        runTest(dispatcher) {
            // The cap exists to stop a big library opening a request per show for a list
            // nobody scrolls to the end of. The star is the user naming the seeds, so it
            // does not apply here - and because the ranking is agreement between seeds,
            // silently dropping some of them would reorder the whole list, not just
            // shorten it.
            val starred = DiscoverViewModel.MAX_SEEDS + 5
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setBody(recommendations(10, 11))
                }

            val library =
                FakeLibrary(
                    // Ids well clear of the suggested ones: a followed show is excluded
                    // from its own tab, so an overlap would empty the list.
                    (101..100 + starred).map { id ->
                        TrackedShow(
                            id = id,
                            name = "Show $id",
                            addedAt = "2026-01-01",
                            favourite = true,
                        )
                    },
                )
            val model = viewModel(library)
            model.load(DiscoverTab.FAVOURITES)
            advanceUntilIdle()

            assertEquals("one request per starred show", starred, server.requestCount)
            // Every seed agreed, so both suggestions carry all of them as the reason.
            assertEquals(
                starred,
                model.state.value.favourites.items
                    .first()
                    .seedCount,
            )
        }

    @Test
    fun `a seed that fails once is retried rather than dropped`() =
        runTest(dispatcher) {
            // Nearly every failure here is transient, and a dropped seed does not shorten
            // the list, it reorders it: the ranking is agreement between seeds.
            var calls = 0
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        calls += 1
                        return if (calls == 1) {
                            MockResponse().setResponseCode(503)
                        } else {
                            MockResponse().setBody(recommendations(10, 11))
                        }
                    }
                }

            val model = viewModel(FakeLibrary(listOf(show(1, favourite = true))))
            model.load(DiscoverTab.FAVOURITES)
            advanceUntilIdle()

            val tab = model.state.value.favourites
            assertEquals("the failed seed is asked again", 2, calls)
            assertEquals(listOf(10, 11), tab.items.map { it.show.id })
            // A retry that succeeded is not worth a caveat.
            assertNull(tab.note)
            assertNull(tab.error)
        }

    @Test
    fun `a seed still failing keeps the previous suggestions rather than reordering them`() =
        runTest(dispatcher) {
            // Seed 1 always answers; seed 2 never does. Keyed on the path, because the two
            // requests are in flight together and their order is not ours to predict.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path.orEmpty().contains("/tv/2/")) {
                            MockResponse().setResponseCode(503)
                        } else {
                            MockResponse().setBody(recommendations(10, 11))
                        }
                }

            val library =
                FakeLibrary(listOf(show(1, favourite = true), show(2, favourite = true)))
            val model = viewModel(library)
            model.load(DiscoverTab.FAVOURITES)
            advanceUntilIdle()

            val first = model.state.value.favourites
            assertEquals(listOf(10, 11), first.items.map { it.show.id })
            // Built from one seed of two, and it says so rather than looking complete.
            assertNotNull("a partial first list must admit it", first.note)

            // Now the same load again, with seed 2 still broken. The list on screen was
            // ranked from a set of seeds, so re-ranking from a different set would rewrite
            // it with something no better that looks just as authoritative.
            model.load(DiscoverTab.FAVOURITES, force = true)
            advanceUntilIdle()

            val second = model.state.value.favourites
            assertEquals(
                "the visible list is untouched",
                first.items.map { it.show.id },
                second.items.map { it.show.id },
            )
            assertTrue(
                "and the tab says why it did not change",
                second.note.orEmpty().startsWith("Kept the previous suggestions"),
            )
            assertNull(second.error)
            assertTrue(second.loaded)
        }

    @Test
    fun `a library with nothing starred asks TMDB nothing`() =
        runTest(dispatcher) {
            val model = viewModel(FakeLibrary(listOf(show(1), show(2))))
            model.load(DiscoverTab.FAVOURITES)
            advanceUntilIdle()

            val tab = model.state.value.favourites
            // An empty result, not an error: there is genuinely nothing to suggest from,
            // and the tab says so rather than reporting a failure that did not happen.
            assertNull(tab.error)
            assertTrue(tab.loaded)
            assertTrue(tab.items.isEmpty())
            assertEquals(0, server.requestCount)
        }

    @Test
    fun `dismissing a suggestion clears it from both seeded tabs`() =
        runTest(dispatcher) {
            // The same show is suggested by the library at large and by the favourite, so
            // it sits in both pools; a dismissal is about the show, not about the tab.
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        MockResponse().setBody(recommendations(10, 11))
                }

            val model = viewModel(FakeLibrary(listOf(show(1, favourite = true))))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()
            model.selectTab(DiscoverTab.FAVOURITES)
            advanceUntilIdle()

            model.dismiss(10)
            advanceUntilIdle()

            assertEquals(
                listOf(11),
                model.state.value.favourites.items
                    .map { it.show.id },
            )
            assertEquals(
                listOf(11),
                model.state.value.forYou.items
                    .map { it.show.id },
            )
        }

    @Test
    fun `a missing key fails the tab rather than throwing`() =
        runTest(dispatcher) {
            val model = DiscoverViewModel(tmdb, FakeKey(null), FakeLibrary(listOf(show(1))))
            model.load(DiscoverTab.FOR_YOU)
            advanceUntilIdle()

            assertNotNull(model.state.value.forYou.error)
            assertEquals(0, server.requestCount)
        }
}
