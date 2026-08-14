import { fetchShows } from '../api/tmdb';
import { latestAiredSeason, todayISO } from './newness';

import type { Season, ShowDetail, TrackedShow } from './types';

/** A season that appeared between two refreshes and is above the watermark. */
export type Discovery = {
  show: TrackedShow;
  season: Season;
};

export type RefreshOutcome = {
  shows: TrackedShow[];
  /** Shows whose latest aired season rose during this refresh. */
  discoveries: Discovery[];
  /** Shows that could not be refreshed, keyed by id, with the failure reason. */
  failures: Map<number, Error>;
};

/**
 * Fold fresh TMDB data into a tracked show, preserving the user's state.
 *
 * `watchedThroughSeason` and `addedAt` are the user's, not TMDB's, and must
 * survive every refresh: overwriting the watermark here would silently mark
 * unwatched seasons as watched.
 */
export function mergeShow(existing: TrackedShow, detail: ShowDetail, now: Date = new Date()): TrackedShow {
  return {
    ...existing,
    name: detail.name,
    posterPath: detail.posterPath,
    firstAirDate: detail.firstAirDate,
    status: detail.status,
    seasons: detail.seasons,
    lastEpisode: detail.lastEpisode,
    nextEpisode: detail.nextEpisode,
    knownAiredSeason:
      latestAiredSeason(detail.seasons, todayISO(now))?.seasonNumber ?? existing.knownAiredSeason,
    lastCheckedAt: now.toISOString(),
  };
}

/**
 * Whether this refresh uncovered a season worth announcing.
 *
 * Announce only on the *transition* — when the latest aired season number
 * actually rises — rather than whenever an unwatched season exists. Otherwise
 * every refresh would re-notify about a season sitting in the backlog.
 *
 * The comparison uses the recorded `knownAiredSeason` rather than recomputing
 * from the previous season list. Recomputing would evaluate an old list against
 * today's date, so a season TMDB had already listed with a future date would
 * look like it had aired all along, and its actual release would never be
 * announced — precisely the case this app exists to catch.
 */
export function findDiscovery(
  before: TrackedShow,
  after: TrackedShow,
  today: string
): Season | null {
  const next = latestAiredSeason(after.seasons, today);
  if (!next) return null;

  if (next.seasonNumber <= before.knownAiredSeason) return null;
  // Already watched: the user got there ahead of us and needs no telling.
  if (next.seasonNumber <= after.watchedThroughSeason) return null;

  return next;
}

/**
 * Refresh every tracked show against TMDB.
 *
 * Shows that fail to fetch keep their previous data rather than being dropped,
 * so a flaky connection degrades the library's freshness but never its
 * contents.
 */
export async function refreshShows(
  apiKey: string,
  shows: TrackedShow[],
  now: Date = new Date()
): Promise<RefreshOutcome> {
  if (shows.length === 0) {
    return { shows, discoveries: [], failures: new Map() };
  }

  const today = todayISO(now);
  const fetched = await fetchShows(
    apiKey,
    shows.map((s) => s.id)
  );

  const failures = new Map<number, Error>();
  const discoveries: Discovery[] = [];

  const updated = shows.map((show) => {
    const result = fetched.get(show.id);
    if (!result || result instanceof Error) {
      if (result) failures.set(show.id, result);
      return show;
    }
    const merged = mergeShow(show, result, now);
    const season = findDiscovery(show, merged, today);
    if (season) discoveries.push({ show: merged, season });
    return merged;
  });

  return { shows: updated, discoveries, failures };
}
