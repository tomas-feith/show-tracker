import type { EpisodeRef, SearchResult, Season, ShowDetail } from '../core/types';

const BASE = 'https://api.themoviedb.org/3';
const IMAGE_BASE = 'https://image.tmdb.org/t/p';

/** Raised for any non-OK TMDB response, carrying the HTTP status. */
export class TmdbError extends Error {
  constructor(
    message: string,
    readonly status: number
  ) {
    super(message);
    this.name = 'TmdbError';
  }
}

/** Poster URL at a sensible width, or null when the show has no artwork. */
export function posterUrl(path: string | null, size: 'w185' | 'w342' | 'w500' = 'w342'): string | null {
  return path ? `${IMAGE_BASE}/${size}${path}` : null;
}

/**
 * TMDB accepts either a v3 API key (a 32-char hex string, passed as a query
 * parameter) or a v4 read access token (a JWT, passed as a bearer header).
 * Users copy whichever one their dashboard shows them, so accept both.
 */
function isV4Token(key: string): boolean {
  return key.startsWith('eyJ');
}

async function request<T>(key: string, path: string, params: Record<string, string> = {}): Promise<T> {
  const url = new URL(BASE + path);
  for (const [k, v] of Object.entries(params)) url.searchParams.set(k, v);

  const headers: Record<string, string> = { accept: 'application/json' };
  if (isV4Token(key)) {
    headers.Authorization = `Bearer ${key}`;
  } else {
    url.searchParams.set('api_key', key);
  }

  let res: Response;
  try {
    res = await fetch(url.toString(), { headers });
  } catch {
    throw new TmdbError('Could not reach TMDB. Check your connection.', 0);
  }

  if (!res.ok) {
    if (res.status === 401) throw new TmdbError('TMDB rejected your API key.', 401);
    if (res.status === 429) throw new TmdbError('Rate limited by TMDB. Try again shortly.', 429);
    throw new TmdbError(`TMDB request failed (${res.status}).`, res.status);
  }
  return (await res.json()) as T;
}

type RawSeason = {
  season_number: number;
  name: string;
  air_date: string | null;
  episode_count: number;
};

type RawEpisode = {
  season_number: number;
  episode_number: number;
  name: string;
  air_date: string | null;
};

/**
 * TMDB returns "" rather than null for missing dates in some fields, so
 * normalise empties to null; the newness logic treats null as "not scheduled".
 */
function cleanDate(value: string | null | undefined): string | null {
  return value && value.length > 0 ? value : null;
}

function toSeason(raw: RawSeason): Season {
  return {
    seasonNumber: raw.season_number,
    name: raw.name,
    airDate: cleanDate(raw.air_date),
    episodeCount: raw.episode_count ?? 0,
  };
}

function toEpisode(raw: RawEpisode | null | undefined): EpisodeRef | null {
  if (!raw) return null;
  return {
    seasonNumber: raw.season_number,
    episodeNumber: raw.episode_number,
    name: raw.name,
    airDate: cleanDate(raw.air_date),
  };
}

/** Search TV shows by name, most relevant first. */
export async function searchShows(key: string, query: string): Promise<SearchResult[]> {
  const trimmed = query.trim();
  if (!trimmed) return [];

  const data = await request<{
    results: {
      id: number;
      name: string;
      overview: string;
      poster_path: string | null;
      first_air_date: string | null;
    }[];
  }>(key, '/search/tv', { query: trimmed, include_adult: 'false' });

  return data.results.map((r) => ({
    id: r.id,
    name: r.name,
    overview: r.overview,
    posterPath: r.poster_path,
    firstAirDate: cleanDate(r.first_air_date),
  }));
}

/** Fetch full detail for one show, including its season list. */
export async function fetchShow(key: string, id: number): Promise<ShowDetail> {
  const data = await request<{
    id: number;
    name: string;
    overview: string;
    poster_path: string | null;
    first_air_date: string | null;
    status: string;
    seasons: RawSeason[];
    last_episode_to_air: RawEpisode | null;
    next_episode_to_air: RawEpisode | null;
  }>(key, `/tv/${id}`);

  return {
    id: data.id,
    name: data.name,
    overview: data.overview,
    posterPath: data.poster_path,
    firstAirDate: cleanDate(data.first_air_date),
    status: data.status,
    seasons: (data.seasons ?? []).map(toSeason),
    lastEpisode: toEpisode(data.last_episode_to_air),
    nextEpisode: toEpisode(data.next_episode_to_air),
  };
}

/** Cheap call used to validate a key the user just pasted. */
export async function verifyKey(key: string): Promise<void> {
  await request(key, '/configuration');
}

/**
 * Fetch many shows with bounded concurrency. TMDB tolerates far more, but a
 * phone on mobile data does not, and a refresh of a large library should not
 * open fifty sockets at once.
 */
export async function fetchShows(
  key: string,
  ids: number[],
  concurrency = 5
): Promise<Map<number, ShowDetail | Error>> {
  const results = new Map<number, ShowDetail | Error>();
  let cursor = 0;

  async function worker(): Promise<void> {
    while (cursor < ids.length) {
      const id = ids[cursor++];
      try {
        results.set(id, await fetchShow(key, id));
      } catch (err) {
        results.set(id, err instanceof Error ? err : new Error(String(err)));
      }
    }
  }

  await Promise.all(Array.from({ length: Math.min(concurrency, ids.length) }, worker));
  return results;
}
