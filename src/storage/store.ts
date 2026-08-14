import AsyncStorage from '@react-native-async-storage/async-storage';
import type { TrackedShow } from '../core/types';

const KEY_SHOWS = 'showtracker:shows:v1';
const KEY_API = 'showtracker:tmdbKey:v1';
const KEY_LAST_CHECK = 'showtracker:lastCheck:v1';

/** Load the followed shows. Returns empty on first run or unreadable data. */
export async function loadShows(): Promise<TrackedShow[]> {
  const raw = await AsyncStorage.getItem(KEY_SHOWS);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? (parsed as TrackedShow[]) : [];
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
