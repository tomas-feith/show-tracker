import AsyncStorage from '@react-native-async-storage/async-storage';
import type { TrackedShow } from '../core/types';

const KEY_SHOWS = 'showtracker:shows:v1';
const KEY_API = 'showtracker:tmdbKey:v1';
const KEY_LAST_CHECK = 'showtracker:lastCheck:v1';

/** The single watermark used before watched-progress and notify-state split. */
type LegacyShow = TrackedShow & { acknowledgedSeason?: number };

/**
 * Bring stored shows up to the current shape.
 *
 * Exported so the upgrade path is testable: this runs against data written by
 * an older install, which is precisely the case normal use cannot reproduce.
 * An undefined watermark would compare falsely against every number, so the
 * backfill matters more than it looks.
 */
export function migrateShows(parsed: unknown): TrackedShow[] {
  if (!Array.isArray(parsed)) return [];

  return (parsed as LegacyShow[])
    .filter((s) => s && typeof s.id === 'number')
    .map(({ acknowledgedSeason, ...s }) => ({
      ...s,
      seasons: Array.isArray(s.seasons) ? s.seasons : [],
      // A pre-split install stored one number serving both roles.
      watchedThroughSeason: s.watchedThroughSeason ?? acknowledgedSeason ?? 0,
      knownAiredSeason: s.knownAiredSeason ?? acknowledgedSeason ?? 0,
    }));
}

/** Load the followed shows. Returns empty on first run or unreadable data. */
export async function loadShows(): Promise<TrackedShow[]> {
  const raw = await AsyncStorage.getItem(KEY_SHOWS);
  if (!raw) return [];
  try {
    return migrateShows(JSON.parse(raw));
  } catch {
    // Corrupt payload: better to start clean than to crash on launch.
    return [];
  }
}

export async function saveShows(shows: TrackedShow[]): Promise<void> {
  await AsyncStorage.setItem(KEY_SHOWS, JSON.stringify(shows));
}

export async function loadApiKey(): Promise<string | null> {
  return AsyncStorage.getItem(KEY_API);
}

export async function saveApiKey(key: string): Promise<void> {
  await AsyncStorage.setItem(KEY_API, key.trim());
}

export async function clearApiKey(): Promise<void> {
  await AsyncStorage.removeItem(KEY_API);
}

/** ISO timestamp of the last completed refresh, for display in the UI. */
export async function loadLastCheck(): Promise<string | null> {
  return AsyncStorage.getItem(KEY_LAST_CHECK);
}

export async function saveLastCheck(iso: string): Promise<void> {
  await AsyncStorage.setItem(KEY_LAST_CHECK, iso);
}
