package com.showtracker.app.domain

// Ranking for the "For you" tab.
//
// TMDB has no recommender that knows the user: `/account/{id}/tv/recommendations` exists,
// but it wants a TMDB login and the user's ratings held on TMDB's servers, and this app
// deliberately has no account and sends nothing but lookups. So the personalisation is
// built here instead, out of the per-show `/tv/{id}/recommendations` lists, which need
// nothing but the API key already configured.

/** One library show's recommendation list, as returned for that show alone. */
data class SeededResults(
    /** The library show the list came from. Shown as the reason for a suggestion. */
    val seedName: String,
    val results: List<SearchResult>,
)

/** A show TMDB suggested, with the library shows that led to it. */
data class Candidate(
    val show: SearchResult,
    /**
     * Names of the library shows whose lists contained this one, in library order. Never
     * empty, and its size is the primary ranking signal.
     */
    val becauseOf: List<String>,
) {
    val seedCount: Int get() = becauseOf.size
}

/**
 * A rating damped towards the middle by how few people voted for it.
 *
 * The standard Bayesian shrink. Without it the ranking's tiebreak is won every time by
 * some obscurity with a single 10/10, which is the opposite of a recommendation.
 */
fun weightedRating(
    voteAverage: Double,
    voteCount: Int,
): Double = (voteCount * voteAverage + PRIOR_VOTES * PRIOR_RATING) / (voteCount + PRIOR_VOTES)

private const val PRIOR_VOTES = 50.0

/** Roughly TMDB's own mean for scripted television; the value an unvoted show regresses to. */
private const val PRIOR_RATING = 6.0

const val DEFAULT_SUGGESTION_COUNT = 30

/**
 * Fold per-show recommendation lists into one ranked list.
 *
 * Ranked by how many library shows produced the same suggestion, because agreement across
 * seeds is a far stronger signal than any single list's ordering: a show that six of yours
 * point at is a better bet than the top entry of one of them. Ties - and with a small
 * library most candidates are tied at one - fall back to [weightedRating], then to the id
 * so the order is stable rather than dependent on map iteration.
 *
 * [exclude] carries the ids already followed. They are dropped outright rather than shown
 * greyed out: the whole question this tab answers is what to watch next, and the library is
 * by definition not an answer to it.
 */
fun rankRecommendations(
    seeds: List<SeededResults>,
    exclude: Set<Int> = emptySet(),
    limit: Int = DEFAULT_SUGGESTION_COUNT,
): List<Candidate> {
    // Insertion-ordered so that, before sorting, candidates sit in library order. Sorting
    // is stable, so that ordering survives as the last tiebreak behind the explicit ones.
    val shows = LinkedHashMap<Int, SearchResult>()
    val reasons = LinkedHashMap<Int, MutableList<String>>()

    for (seed in seeds) {
        // One seed listing the same show twice must not count twice; TMDB paginates, and a
        // caller stitching pages together can hand us a duplicate.
        val seen = HashSet<Int>()
        for (result in seed.results) {
            if (result.id in exclude || !seen.add(result.id)) continue
            shows.putIfAbsent(result.id, result)
            reasons.getOrPut(result.id) { mutableListOf() }.add(seed.seedName)
        }
    }

    return reasons
        .map { (id, names) -> Candidate(shows.getValue(id), names.toList()) }
        .sortedWith(
            compareByDescending<Candidate> { it.seedCount }
                .thenByDescending { weightedRating(it.show.voteAverage, it.show.voteCount) }
                .thenBy { it.show.id },
        ).take(limit)
}

/**
 * How a suggestion explains itself: at most two seed names, then a count for the rest.
 *
 * Two because the row has one line for it, and "Because you follow A, B and 4 others" is
 * already the point - the exact membership of the tail tells the reader nothing they act
 * on.
 */
fun describeReason(becauseOf: List<String>): String =
    when (becauseOf.size) {
        0 -> {
            ""
        }

        1 -> {
            "Because you follow ${becauseOf[0]}"
        }

        2 -> {
            "Because you follow ${becauseOf[0]} and ${becauseOf[1]}"
        }

        else -> {
            val rest = becauseOf.size - 2
            val others = if (rest == 1) "1 other" else "$rest others"
            "Because you follow ${becauseOf[0]}, ${becauseOf[1]} and $others"
        }
    }
