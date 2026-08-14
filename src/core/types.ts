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
 * `acknowledgedSeason` is the crux of the whole app: it records the highest
 * season number the user has already seen us report. A show is "new" precisely
 * when a season has aired above that watermark. It is initialised on add to the
 * latest already-aired season, so adding a 9-season show doesn't announce
 * seasons 1-9 as news.
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
  acknowledgedSeason: number;
  addedAt: string; // ISO timestamp
  lastCheckedAt: string | null; // ISO timestamp
};

/** How a tracked show should be presented, derived fresh from its data. */
export type ShowState =
  | { kind: 'new_season'; season: Season; daysAgo: number }
  | { kind: 'airing'; next: EpisodeRef; daysUntil: number }
  | { kind: 'upcoming'; season: Season; daysUntil: number }
  | { kind: 'waiting' } // returning series, nothing scheduled yet
  | { kind: 'ended' };
