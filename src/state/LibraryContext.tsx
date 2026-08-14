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
  acknowledge: (id: number) => Promise<void>;
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

  /** Persist and update in one step so state and storage cannot drift apart. */
  const commit = useCallback(async (next: TrackedShow[]) => {
    setShows(next);
    await saveShows(next);
  }, []);

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
      if (shows.some((s) => s.id === id)) return;

      const detail = await fetchShow(apiKey, id);
      const today = todayISO();
      const now = new Date().toISOString();

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
        acknowledgedSeason: initialWatermark(detail.seasons, today),
        addedAt: now,
        lastCheckedAt: now,
      };

      await commit([...shows, tracked]);
    },
    [apiKey, shows, commit]
  );

  const removeShow = useCallback(
    async (id: number) => {
      await commit(shows.filter((s) => s.id !== id));
    },
    [shows, commit]
  );

  /** Mark the current latest season as seen, clearing the "new" badge. */
  const acknowledge = useCallback(
    async (id: number) => {
      const today = todayISO();
      await commit(
        shows.map((s) => {
          if (s.id !== id) return s;
          const latest = latestAiredSeason(s.seasons, today);
          return latest ? { ...s, acknowledgedSeason: latest.seasonNumber } : s;
        })
      );
    },
    [shows, commit]
  );

  const refresh = useCallback(async () => {
    if (!apiKey || refreshing) return;
    setRefreshing(true);
    setError(null);
    try {
      const outcome = await refreshShows(apiKey, shows);
      await commit(outcome.shows);

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
  }, [apiKey, refreshing, shows, commit]);

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
      acknowledge,
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
      acknowledge,
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
