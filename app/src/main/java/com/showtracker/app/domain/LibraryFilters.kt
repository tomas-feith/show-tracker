package com.showtracker.app.domain

import java.time.LocalDate

// Ways of narrowing the library, and the shows that survive them. Separate from the search
// box, which asks a different question: search finds a show whose name you already know,
// and these find the shows you could not have named. They compose - a search runs inside
// whatever filters are set - and both run before sorting.

/**
 * The states worth filtering by, which are fewer than [ShowState] has.
 *
 * [ShowState.Airing] and [ShowState.Running] fold together for the same reason they share a
 * sort weight: they are one show on either side of TMDB publishing its next episode, and
 * separating them would make a show fall out of a filter for a few days each week without
 * anything about it having changed.
 */
enum class StateGroup(
    val label: String,
) {
    WATCHING("Watching"),
    BEHIND("Behind"),
    AIRING("Airing"),
    UPCOMING("Upcoming"),
    WAITING("No date yet"),
    ENDED("Ended"),
}

/** Which group a show is in today. */
fun stateGroup(
    show: TrackedShow,
    today: LocalDate,
): StateGroup =
    when (showState(show, today)) {
        is ShowState.Watching -> StateGroup.WATCHING
        is ShowState.Behind -> StateGroup.BEHIND
        is ShowState.Airing -> StateGroup.AIRING
        is ShowState.Running -> StateGroup.AIRING
        is ShowState.Upcoming -> StateGroup.UPCOMING
        ShowState.Waiting -> StateGroup.WAITING
        ShowState.Ended -> StateGroup.ENDED
    }

/**
 * Episode length bands, for picking by how much of an evening a show costs.
 *
 * Boundaries at 30 and 45 minutes because that is where television actually sits: a sitcom
 * around 22-30, an hour drama at 42-50 once the adverts are gone, and prestige or streaming
 * episodes above that. [matches] is inclusive at the bottom and exclusive at the top so no
 * runtime falls in two bands or in none.
 */
enum class RuntimeBand(
    val label: String,
    private val range: IntRange,
) {
    SHORT(label = "Under 30m", range = 0 until 30),
    MEDIUM(label = "30-45m", range = 30 until 46),
    LONG(label = "Over 45m", range = 46..Int.MAX_VALUE),
    ;

    fun matches(minutes: Int): Boolean = minutes in range
}

/** Score thresholds, as a menu rather than a slider: nobody filters at 7.3. */
enum class ScoreFloor(
    val label: String,
    val minimum: Double,
) {
    GOOD(label = "7.0+", minimum = 7.0),
    GREAT(label = "8.0+", minimum = 8.0),
    BEST(label = "9.0+", minimum = 9.0),
}

/**
 * Everything narrowing the list at once. All of it empty is the whole library.
 *
 * Within an axis the sets are OR - a show tagged Drama survives a filter for Drama or
 * Comedy - and across axes they are AND. That is what people mean by picking two genres and
 * a runtime: something from either genre, that is also short.
 */
data class LibraryFilters(
    val states: Set<StateGroup> = emptySet(),
    val genres: Set<String> = emptySet(),
    val runtime: RuntimeBand? = null,
    val score: ScoreFloor? = null,
) {
    /** Whether anything is being hidden, which is what the screen has to disclose. */
    val active: Boolean
        get() = states.isNotEmpty() || genres.isNotEmpty() || runtime != null || score != null
}

/**
 * Apply the filters.
 *
 * A show TMDB has told us nothing about is excluded by an active runtime or score filter
 * rather than kept. Keeping it would answer "show me things over 8" with a list containing
 * shows that have no score at all, which is not what was asked - and the honest place to
 * notice that is the "showing N of M" line, not a quietly padded list.
 */
fun applyFilters(
    shows: List<TrackedShow>,
    filters: LibraryFilters,
    today: LocalDate,
): List<TrackedShow> {
    if (!filters.active) return shows

    return shows.filter { show ->
        val byState =
            filters.states.isEmpty() || stateGroup(show, today) in filters.states
        val byGenre =
            filters.genres.isEmpty() || show.genres.any { it in filters.genres }
        val byRuntime =
            filters.runtime == null ||
                show.episodeRunTime?.let { filters.runtime.matches(it) } == true
        val byScore =
            filters.score == null ||
                (show.voteCount > 0 && show.voteAverage >= filters.score.minimum)

        byState && byGenre && byRuntime && byScore
    }
}

/**
 * Every genre present in the library, in alphabetical order.
 *
 * Built from the shows rather than from TMDB's full genre list, so the menu never offers a
 * filter that would empty the screen. It also means the menu is empty until a refresh has
 * filled the column in, which is the truth: there is nothing to filter by yet.
 */
fun libraryGenres(shows: List<TrackedShow>): List<String> =
    shows
        .flatMap { it.genres }
        .distinct()
        .sorted()
