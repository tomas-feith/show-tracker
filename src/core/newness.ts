import type { EpisodeRef, Season, ShowState, TrackedShow } from './types';

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/** Today as "YYYY-MM-DD" in the device's local timezone. */
export function todayISO(now: Date = new Date()): string {
  const y = now.getFullYear();
  const m = String(now.getMonth() + 1).padStart(2, '0');
  const d = String(now.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * Whole days from `from` to `to`, both "YYYY-MM-DD". Positive means `to` is
 * later. Both are read as UTC midnight so the result is never skewed by the
 * device timezone or by daylight saving transitions.
 */
export function daysBetween(from: string, to: string): number {
  const a = Date.parse(`${from}T00:00:00Z`);
  const b = Date.parse(`${to}T00:00:00Z`);
  return Math.round((b - a) / MS_PER_DAY);
}

/** True when `date` is today or earlier. A null date has not happened. */
export function hasHappened(date: string | null, today: string): boolean {
  if (!date) return false;
  return daysBetween(date, today) >= 0;
}

/**
 * Real seasons only: TMDB files specials, recaps and shorts under season 0,
 * which must never count as a new season.
 */
export function realSeasons(seasons: Season[]): Season[] {
  return seasons.filter((s) => s.seasonNumber >= 1);
}

/**
 * Whether a single season has actually started airing: a real season number, a
 * past air date, and at least one episode. The single definition of "aired",
 * used everywhere so the badge, the backlog count and the notification can
 * never disagree.
 */
export function hasAired(season: Season, today: string): boolean {
  return season.seasonNumber >= 1 && season.episodeCount > 0 && hasHappened(season.airDate, today);
}

/**
 * The highest-numbered season that has actually started airing.
 *
 * TMDB routinely lists a future season months before release (sometimes with a
 * null air date, sometimes with a dated placeholder), so an unfiltered "max
 * season number" would announce a new season that nobody can watch yet. We
 * additionally require at least one episode, because empty placeholder seasons
 * occasionally carry a past air date.
 */
export function latestAiredSeason(seasons: Season[], today: string): Season | null {
  const aired = seasons.filter((s) => hasAired(s, today));
  if (aired.length === 0) return null;
  return aired.reduce((best, s) => (s.seasonNumber > best.seasonNumber ? s : best));
}

/** The nearest season that is announced but has not started airing yet. */
export function nextUnairedSeason(seasons: Season[], today: string): Season | null {
  const upcoming = realSeasons(seasons).filter(
    (s) => s.airDate !== null && !hasHappened(s.airDate, today)
  );
  if (upcoming.length === 0) return null;
  return upcoming.reduce((best, s) => (s.seasonNumber < best.seasonNumber ? s : best));
}

/**
 * The season number to record as "already known" when a show is first added,
 * so that following a long-running show does not immediately report news.
 */
export function initialWatermark(seasons: Season[], today: string): number {
  return latestAiredSeason(seasons, today)?.seasonNumber ?? 0;
}

/**
 * How many aired seasons the user has not watched.
 *
 * Counts only seasons that actually exist and have aired, so a watermark left
 * behind by a show that later removed a season cannot report a negative or
 * inflated backlog.
 */
export function seasonsBehind(show: TrackedShow, today: string): number {
  return show.seasons.filter(
    (s) => hasAired(s, today) && s.seasonNumber > show.watchedThroughSeason
  ).length;
}

/** True when there is at least one aired season the user has not watched. */
export function isBehind(show: TrackedShow, today: string): boolean {
  return seasonsBehind(show, today) > 0;
}

/**
 * Derive how a show should be presented. Precedence is deliberate: an unwatched
 * aired season outranks everything, since catching up is the point of the app.
 */
export function showState(show: TrackedShow, today: string): ShowState {
  const latest = latestAiredSeason(show.seasons, today);
  const behind = seasonsBehind(show, today);

  if (latest && behind > 0) {
    return {
      kind: 'behind',
      latest,
      seasonsBehind: behind,
      daysAgo: latest.airDate ? daysBetween(latest.airDate, today) : 0,
    };
  }

  const next = show.nextEpisode;
  if (next && next.airDate && !hasHappened(next.airDate, today)) {
    return { kind: 'airing', next, daysUntil: daysBetween(today, next.airDate) };
  }

  const upcoming = nextUnairedSeason(show.seasons, today);
  if (upcoming && upcoming.airDate) {
    return {
      kind: 'upcoming',
      season: upcoming,
      daysUntil: daysBetween(today, upcoming.airDate),
    };
  }

  if (show.status === 'Ended' || show.status === 'Canceled') {
    return { kind: 'ended' };
  }

  return { kind: 'waiting' };
}

/** Sort weight per state: lower sorts first. */
const STATE_ORDER: Record<ShowState['kind'], number> = {
  behind: 0,
  airing: 1,
  upcoming: 2,
  waiting: 3,
  ended: 4,
};

/**
 * Order the library so the things demanding attention float to the top:
 * unseen new seasons first (most recent drop first), then imminent episodes,
 * then announced seasons by nearness, then everything dormant by name.
 */
export function sortLibrary(shows: TrackedShow[], today: string): TrackedShow[] {
  return [...shows].sort((a, b) => {
    const sa = showState(a, today);
    const sb = showState(b, today);
    const byKind = STATE_ORDER[sa.kind] - STATE_ORDER[sb.kind];
    if (byKind !== 0) return byKind;

    if (sa.kind === 'behind' && sb.kind === 'behind') {
      // Most recent drop first; a deeper backlog breaks a same-day tie.
      if (sa.daysAgo !== sb.daysAgo) return sa.daysAgo - sb.daysAgo;
      if (sa.seasonsBehind !== sb.seasonsBehind) return sb.seasonsBehind - sa.seasonsBehind;
    }
    if (sa.kind === 'airing' && sb.kind === 'airing') {
      if (sa.daysUntil !== sb.daysUntil) return sa.daysUntil - sb.daysUntil;
    }
    if (sa.kind === 'upcoming' && sb.kind === 'upcoming') {
      if (sa.daysUntil !== sb.daysUntil) return sa.daysUntil - sb.daysUntil;
    }
    return a.name.localeCompare(b.name);
  });
}

/** Human-readable relative day count, e.g. "today", "in 3 days", "5 days ago". */
export function describeDays(days: number, direction: 'ago' | 'until'): string {
  if (days === 0) return 'today';
  const n = Math.abs(days);
  if (direction === 'ago') {
    if (n === 1) return 'yesterday';
    if (n < 30) return `${n} days ago`;
    if (n < 365) return `${Math.round(n / 30)} months ago`;
    const years = Math.floor(n / 365);
    return years === 1 ? 'over a year ago' : `${years} years ago`;
  }
  if (n === 1) return 'tomorrow';
  if (n < 30) return `in ${n} days`;
  if (n < 365) return `in ${Math.round(n / 30)} months`;
  return 'over a year away';
}

/** Format an episode marker as "S02E05". */
export function formatEpisode(ep: EpisodeRef): string {
  const s = String(ep.seasonNumber).padStart(2, '0');
  const e = String(ep.episodeNumber).padStart(2, '0');
  return `S${s}E${e}`;
}
