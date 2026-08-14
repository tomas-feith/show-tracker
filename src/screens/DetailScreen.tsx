import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import React, { useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { fetchShow } from '../api/tmdb';
import { Poster } from '../components/Poster';
import {
  describeDays,
  formatEpisode,
  hasAired,
  isBehind,
  realSeasons,
  seasonsBehind,
  showState,
  todayISO,
} from '../core/newness';
import { mergeShow } from '../core/refresh';
import type { ShowDetail, TrackedShow } from '../core/types';
import type { RootStackParamList } from '../navigation/types';
import { useLibrary } from '../state/LibraryContext';
import { colors, radius, spacing } from '../theme';

type Props = NativeStackScreenProps<RootStackParamList, 'Detail'>;

/** Present an untracked show in the same shape as a tracked one for rendering. */
function asPreview(detail: ShowDetail): TrackedShow {
  return {
    ...detail,
    // A preview has no watermark, so nothing can read as backlog here.
    watchedThroughSeason: Number.MAX_SAFE_INTEGER,
    knownAiredSeason: Number.MAX_SAFE_INTEGER,
    addedAt: '',
    lastCheckedAt: null,
  };
}

export function DetailScreen({ route, navigation }: Props) {
  const { id } = route.params;
  const { apiKey, shows, addShow, removeShow, setWatchedThrough, markCaughtUp } = useLibrary();
  const today = todayISO();

  const tracked = shows.find((s) => s.id === id) ?? null;
  const [fresh, setFresh] = useState<ShowDetail | null>(null);
  const [loading, setLoading] = useState(!tracked);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // Always fetch once: tracked shows carry no overview text, and an untracked
  // show has no local data at all.
  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!apiKey) {
        setLoading(false);
        return;
      }
      try {
        const detail = await fetchShow(apiKey, id);
        if (cancelled) return;
        setFresh(detail);
        setError(null);
      } catch (err) {
        // A tracked show already has everything but the overview, so a failed
        // background fetch is not worth an error banner over a usable screen.
        if (!cancelled && !tracked) {
          setError(err instanceof Error ? err.message : 'Could not load show.');
        }
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
    // `tracked` is read only to decide whether to surface an error, and
    // re-running on every library change would refetch on each tap.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, apiKey]);

  const overview = fresh?.overview ?? '';

  // Prefer the fetch we just made, folded onto the user's own progress. Showing
  // `tracked` as-is would display a stale season list while fresher data sat
  // unused in memory.
  const show: TrackedShow | null = fresh
    ? tracked
      ? mergeShow(tracked, fresh)
      : asPreview(fresh)
    : tracked;

  useEffect(() => {
    if (show) navigation.setOptions({ title: show.name });
  }, [show, navigation]);

  if (loading && !show) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color={colors.accent} />
      </View>
    );
  }

  if (!show) {
    return (
      <View style={styles.centered}>
        <Text style={styles.error}>{error ?? 'Show not found.'}</Text>
      </View>
    );
  }

  const state = showState(show, today);
  const seasons = [...realSeasons(show.seasons)].sort((a, b) => b.seasonNumber - a.seasonNumber);
  const behindBy = tracked ? seasonsBehind(tracked, today) : 0;
  const showsNew = tracked ? isBehind(tracked, today) : false;

  async function onFollow() {
    setBusy(true);
    try {
      await addShow(id);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not follow show.');
    } finally {
      setBusy(false);
    }
  }

  function onUnfollow() {
    Alert.alert('Stop following?', `Remove ${show!.name} from your list?`, [
      { text: 'Cancel', style: 'cancel' },
      {
        text: 'Remove',
        style: 'destructive',
        onPress: () => {
          void removeShow(id).then(() => navigation.goBack());
        },
      },
    ]);
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.header}>
        <Poster path={show.posterPath} name={show.name} width={110} />
        <View style={styles.headerBody}>
          <Text style={styles.title}>{show.name}</Text>
          <Text style={styles.sub}>
            {show.firstAirDate ? show.firstAirDate.slice(0, 4) : 'Unknown'} &middot; {show.status}
          </Text>
          <Text style={styles.sub}>
            {seasons.length} season{seasons.length === 1 ? '' : 's'}
          </Text>
        </View>
      </View>

      <View
        style={[
          styles.statusCard,
          state.kind === 'behind' && tracked ? styles.statusCardNew : null,
        ]}
      >
        {state.kind === 'behind' && tracked && (
          <Text style={styles.statusStrong}>
            {state.seasonsBehind === 1
              ? `Season ${state.latest.seasonNumber} arrived ${describeDays(state.daysAgo, 'ago')}.`
              : // "through season N" read as though the user had watched that
                // far, contradicting the ticks below. Name it as the newest.
                `You are ${state.seasonsBehind} seasons behind. Season ${state.latest.seasonNumber} is the newest.`}
          </Text>
        )}
        {state.kind === 'airing' && (
          <Text style={styles.statusStrong}>
            {formatEpisode(state.next)} &ldquo;{state.next.name}&rdquo; airs{' '}
            {describeDays(state.daysUntil, 'until')}.
          </Text>
        )}
        {state.kind === 'upcoming' && (
          <Text style={styles.statusStrong}>
            Season {state.season.seasonNumber} starts {describeDays(state.daysUntil, 'until')}.
          </Text>
        )}
        {state.kind === 'waiting' && (
          <Text style={styles.statusMuted}>Still running, but nothing is scheduled yet.</Text>
        )}
        {state.kind === 'ended' && (
          <Text style={styles.statusMuted}>This show has finished its run.</Text>
        )}
        {show.lastEpisode && (
          <Text style={styles.statusMuted}>
            Last aired: {formatEpisode(show.lastEpisode)}
            {show.lastEpisode.airDate ? ` on ${show.lastEpisode.airDate}` : ''}
          </Text>
        )}
      </View>

      <View style={styles.actions}>
        {tracked ? (
          <>
            {showsNew && (
              <Pressable style={styles.primaryBtn} onPress={() => void markCaughtUp(id)}>
                <Text style={styles.primaryText}>
                  {behindBy === 1 ? 'Mark watched' : 'Caught up'}
                </Text>
              </Pressable>
            )}
            <Pressable style={styles.secondaryBtn} onPress={onUnfollow}>
              <Text style={styles.secondaryText}>Unfollow</Text>
            </Pressable>
          </>
        ) : (
          <Pressable style={styles.primaryBtn} disabled={busy} onPress={() => void onFollow()}>
            {busy ? (
              <ActivityIndicator size="small" color="#fff" />
            ) : (
              <Text style={styles.primaryText}>Follow this show</Text>
            )}
          </Pressable>
        )}
      </View>

      {error && <Text style={styles.error}>{error}</Text>}

      {overview.length > 0 && <Text style={styles.overview}>{overview}</Text>}

      <Text style={styles.sectionTitle}>Seasons</Text>
      {tracked && (
        <Text style={styles.sectionHint}>
          Tap the last season you finished. Tapping it again clears it back to unwatched.
        </Text>
      )}

      {seasons.map((season) => {
        const watched = tracked ? season.seasonNumber <= tracked.watchedThroughSeason : false;
        const aired = hasAired(season, today);
        const isUnseen = tracked ? !watched && aired : false;

        // Tapping the current mark clears it, so a mis-tap is undoable without
        // a separate control.
        const onTap = () => {
          if (!tracked) return;
          const target =
            tracked.watchedThroughSeason === season.seasonNumber ? season.seasonNumber - 1 : season.seasonNumber;
          void setWatchedThrough(id, target);
        };

        return (
          <Pressable
            key={season.seasonNumber}
            onPress={onTap}
            disabled={!tracked}
            accessibilityRole={tracked ? 'button' : undefined}
            accessibilityState={tracked ? { selected: watched } : undefined}
            accessibilityLabel={
              tracked ? `${season.name}, ${watched ? 'watched' : 'not watched'}` : season.name
            }
            style={({ pressed }) => [
              styles.seasonRow,
              watched && styles.seasonRowWatched,
              pressed && styles.pressed,
            ]}
          >
            {tracked && (
              <Text style={[styles.check, watched && styles.checkOn]}>{watched ? '✓' : '○'}</Text>
            )}
            <View style={styles.seasonBody}>
              <Text style={[styles.seasonName, watched && styles.seasonNameWatched]}>
                {season.name}
              </Text>
              <Text style={styles.seasonMeta}>
                {season.airDate ?? 'No date yet'} &middot; {season.episodeCount} ep
                {season.episodeCount === 1 ? '' : 's'}
              </Text>
            </View>
            {isUnseen && <Text style={styles.seasonFlag}>NEW</Text>}
          </Pressable>
        );
      })}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  content: {
    padding: spacing.lg,
    gap: spacing.lg,
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: colors.bg,
    padding: spacing.xl,
  },
  header: {
    flexDirection: 'row',
    gap: spacing.lg,
  },
  headerBody: {
    flex: 1,
    gap: spacing.xs,
    justifyContent: 'center',
  },
  title: {
    color: colors.text,
    fontSize: 20,
    fontWeight: '700',
  },
  sub: {
    color: colors.textMuted,
    fontSize: 13,
  },
  statusCard: {
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.lg,
    gap: spacing.xs,
  },
  statusCardNew: {
    borderColor: colors.new,
  },
  statusStrong: {
    color: colors.text,
    fontSize: 15,
    fontWeight: '600',
    lineHeight: 21,
  },
  statusMuted: {
    color: colors.textMuted,
    fontSize: 13,
  },
  actions: {
    flexDirection: 'row',
    gap: spacing.md,
  },
  primaryBtn: {
    flex: 1,
    backgroundColor: colors.accent,
    borderRadius: radius.md,
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  primaryText: {
    color: '#fff',
    fontWeight: '700',
    fontSize: 15,
  },
  secondaryBtn: {
    flex: 1,
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.md,
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  secondaryText: {
    color: colors.textMuted,
    fontWeight: '600',
    fontSize: 15,
  },
  overview: {
    color: colors.textMuted,
    fontSize: 14,
    lineHeight: 21,
  },
  sectionTitle: {
    color: colors.text,
    fontSize: 16,
    fontWeight: '700',
  },
  sectionHint: {
    color: colors.textFaint,
    fontSize: 12,
    lineHeight: 17,
    marginTop: -spacing.sm,
  },
  seasonRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.md,
    marginTop: -spacing.sm,
  },
  seasonRowWatched: {
    backgroundColor: colors.surfaceAlt,
    borderColor: colors.surfaceAlt,
  },
  pressed: {
    opacity: 0.7,
  },
  check: {
    fontSize: 15,
    color: colors.textFaint,
    width: 18,
    textAlign: 'center',
  },
  checkOn: {
    color: colors.airing,
  },
  seasonBody: {
    flex: 1,
    gap: 2,
  },
  seasonName: {
    color: colors.text,
    fontSize: 14,
    fontWeight: '600',
  },
  seasonNameWatched: {
    color: colors.textMuted,
  },
  seasonMeta: {
    color: colors.textFaint,
    fontSize: 12,
  },
  seasonFlag: {
    color: colors.new,
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
  error: {
    color: colors.danger,
    fontSize: 13,
  },
});
