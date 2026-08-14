/** A single season as reported by TMDB. */
export type Season = {
  seasonNumber: number;
  name: string;
  airDate: string | null; // ISO date, "YYYY-MM-DD"
  episodeCount: number;
};

/** A specific episode, used for "last aired" / "next airing" markers. */
export type EpisodeRef = {
  seasonNumber: number;
  episodeNumber: number;
  name: string;
  airDate: string | null;
};

/** Full detail for a show, as fetched from TMDB. */
export type ShowDetail = {
  id: number;
  name: string;
  overview: string;
  posterPath: string | null;
  firstAirDate: string | null;
  /** TMDB production status, e.g. "Returning Series", "Ended", "Canceled". */
  status: string;
  seasons: Season[];
  lastEpisode: EpisodeRef | null;
  nextEpisode: EpisodeRef | null;
};

/** A search hit, which carries less data than a full detail fetch. */
export type SearchResult = {
  id: number;
  name: string;
  overview: string;
  posterPath: string | null;
  firstAirDate: string | null;
};

/**
 * A show the user follows. This is the persisted shape.
 *
 * Two separate watermarks, deliberately not merged, because they answer
 * different questions:
 *
 * - `watchedThroughSeason` is the user's own progress, and drives how far
 *   behind they are.
 * - `knownAiredSeason` is what the app has already told them about, and only
 *   stops the same season being announced twice.
 *
 * Collapsing them would mean dismissing a notification silently claimed you
 * had watched the season, or that marking a season watched suppressed the
 * alert for the next one.
 */
export type TrackedShow = {
  id: number;
  name: string;
  posterPath: string | null;
  firstAirDate: string | null;
  status: string;
  seasons: Season[];
  lastEpisode: EpisodeRef | null;
  nextEpisode: EpisodeRef | null;
  /**
   * The highest season the user says they have finished. 0 means not started.
   * Anything aired above this is backlog.
   */
  watchedThroughSeason: number;
  /**
   * The latest aired season number as observed at the last check.
   *
   * Recorded rather than recomputed: the stored season list is always
   * re-evaluated against today's date, so a season TMDB listed months early
   * would appear to have "always been aired" once its date arrives, and the
   * moment it actually dropped would pass unnoticed.
   */
  knownAiredSeason: number;
  addedAt: string; // ISO timestamp
  lastCheckedAt: string | null; // ISO timestamp
};

/** How a tracked show should be presented, derived fresh from its data. */
export type ShowState =
  /** Aired seasons the user has not watched. `seasonsBehind` is at least 1. */
  | { kind: 'behind'; latest: Season; seasonsBehind: number; daysAgo: number }
  | { kind: 'airing'; next: EpisodeRef; daysUntil: number }
  | { kind: 'upcoming'; season: Season; daysUntil: number }
  | { kind: 'waiting' } // returning series, nothing scheduled yet
  | { kind: 'ended' };
