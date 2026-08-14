import React, { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { fetchShow } from '../api/tmdb';
import { initialWatermark, latestAiredSeason, todayISO } from '../core/newness';
import { refreshShows } from '../core/refresh';
import type { TrackedShow } from '../core/types';
import { notifyDiscoveries } from '../notifications/notify';
import {
  clearApiKey,
  loadApiKey,
  loadLastCheck,
  loadShows,
  saveApiKey,
  saveLastCheck,
  saveShows,
} from '../storage/store';

type LibraryValue = {
  ready: boolean;
  apiKey: string | null;
  shows: TrackedShow[];
  refreshing: boolean;
  lastCheck: string | null;
  error: string | null;
  setApiKey: (key: string) => Promise<void>;
  forgetApiKey: () => Promise<void>;
  isTracked: (id: number) => boolean;
  addShow: (id: number) => Promise<void>;
  removeShow: (id: number) => Promise<void>;
  /** Record that the user has watched through `seasonNumber` (0 = not started). */
  setWatchedThrough: (id: number, seasonNumber: number) => Promise<void>;
  /** Shorthand for "I'm up to date": watched through the latest aired season. */
  markCaughtUp: (id: number) => Promise<void>;
  refresh: () => Promise<void>;
};

const LibraryContext = createContext<LibraryValue | null>(null);

export function LibraryProvider({ children }: { children: React.ReactNode }) {
  const [ready, setReady] = useState(false);
  const [apiKey, setApiKeyState] = useState<string | null>(null);
  const [shows, setShows] = useState<TrackedShow[]>([]);
  const [refreshing, setRefreshing] = useState(false);
  const [lastCheck, setLastCheck] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    (async () => {
      const [key, stored, check] = await Promise.all([loadApiKey(), loadShows(), loadLastCheck()]);
      setApiKeyState(key);
      setShows(stored);
      setLastCheck(check);
      setReady(true);
    })();
  }, []);

  // Persist whenever the library changes, rather than at each call site, so
  // storage cannot drift from state. Skipped until the initial load completes,
  // which would otherwise write an empty array over real data.
  useEffect(() => {
    if (!ready) return;
    void saveShows(shows);
  }, [shows, ready]);

  const setApiKey = useCallback(async (key: string) => {
    await saveApiKey(key);
    setApiKeyState(key.trim());
  }, []);

  const forgetApiKey = useCallback(async () => {
    await clearApiKey();
    setApiKeyState(null);
  }, []);

  const isTracked = useCallback((id: number) => shows.some((s) => s.id === id), [shows]);

  const addShow = useCallback(
    async (id: number) => {
      if (!apiKey) throw new Error('No TMDB key configured.');

      const detail = await fetchShow(apiKey, id);
      const today = todayISO();
      const now = new Date().toISOString();
      const watermark = initialWatermark(detail.seasons, today);

      const tracked: TrackedShow = {
        id: detail.id,
        name: detail.name,
        posterPath: detail.posterPath,
        firstAirDate: detail.firstAirDate,
        status: detail.status,
        seasons: detail.seasons,
        lastEpisode: detail.lastEpisode,
        nextEpisode: detail.nextEpisode,
        // Start level with the current season so adding a show is quiet.
        watchedThroughSeason: watermark,
        knownAiredSeason: watermark,
        addedAt: now,
        lastCheckedAt: now,
      };

      // Functional update: the fetch above is slow enough that the library may
      // have changed underneath us. The guard also runs here, so a double tap
      // cannot add the same show twice.
      setShows((prev) => (prev.some((s) => s.id === id) ? prev : [...prev, tracked]));
    },
    [apiKey]
  );

  const removeShow = useCallback(async (id: number) => {
    setShows((prev) => prev.filter((s) => s.id !== id));
  }, []);

  const setWatchedThrough = useCallback(async (id: number, seasonNumber: number) => {
    setShows((prev) =>
      prev.map((s) =>
        s.id === id ? { ...s, watchedThroughSeason: Math.max(0, seasonNumber) } : s
      )
    );
  }, []);

  const markCaughtUp = useCallback(async (id: number) => {
    const today = todayISO();
    setShows((prev) =>
      prev.map((s) => {
        if (s.id !== id) return s;
        const latest = latestAiredSeason(s.seasons, today);
        return latest ? { ...s, watchedThroughSeason: latest.seasonNumber } : s;
      })
    );
  }, []);

  const refresh = useCallback(async () => {
    if (!apiKey || refreshing) return;
    setRefreshing(true);
    setError(null);
    try {
      const outcome = await refreshShows(apiKey, shows);

      // Fold results in by id instead of replacing wholesale: a show followed
      // or removed while the network call was in flight must survive.
      const byId = new Map(outcome.shows.map((s) => [s.id, s]));
      setShows((prev) => prev.map((s) => byId.get(s.id) ?? s));

      const stamp = new Date().toISOString();
      await saveLastCheck(stamp);
      setLastCheck(stamp);

      if (outcome.failures.size > 0) {
        const first = [...outcome.failures.values()][0];
        setError(
          outcome.failures.size === 1
            ? first.message
            : `${outcome.failures.size} shows failed to update. ${first.message}`
        );
      }
      await notifyDiscoveries(outcome.discoveries);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Refresh failed.');
    } finally {
      setRefreshing(false);
    }
  }, [apiKey, refreshing, shows]);

  const value = useMemo(
    () => ({
      ready,
      apiKey,
      shows,
      refreshing,
      lastCheck,
      error,
      setApiKey,
      forgetApiKey,
      isTracked,
      addShow,
      removeShow,
      setWatchedThrough,
      markCaughtUp,
      refresh,
    }),
    [
      ready,
      apiKey,
      shows,
      refreshing,
      lastCheck,
      error,
      setApiKey,
      forgetApiKey,
      isTracked,
      addShow,
      removeShow,
      setWatchedThrough,
      markCaughtUp,
      refresh,
    ]
  );

  return <LibraryContext.Provider value={value}>{children}</LibraryContext.Provider>;
}

export function useLibrary(): LibraryValue {
  const ctx = useContext(LibraryContext);
  if (!ctx) throw new Error('useLibrary must be used inside a LibraryProvider.');
  return ctx;
}
