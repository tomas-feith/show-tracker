import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import React, { useCallback, useEffect, useRef } from 'react';
import {
  ActivityIndicator,
  AppState,
  FlatList,
  Pressable,
  RefreshControl,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { ShowRow } from '../components/ShowRow';
import { isBehind, sortLibrary, todayISO } from '../core/newness';
import type { RootStackParamList } from '../navigation/types';
import { useLibrary } from '../state/LibraryContext';
import { colors, radius, spacing } from '../theme';

type Props = NativeStackScreenProps<RootStackParamList, 'Library'>;

/** Don't hammer TMDB on every glance at the app; a few hours is fresh enough. */
const STALE_AFTER_MS = 6 * 60 * 60 * 1000;

function isStale(lastCheck: string | null): boolean {
  if (!lastCheck) return true;
  const at = Date.parse(lastCheck);
  if (Number.isNaN(at)) return true;
  return Date.now() - at > STALE_AFTER_MS;
}

function formatLastCheck(iso: string | null): string {
  if (!iso) return 'Never checked';
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return 'Never checked';
  const mins = Math.round((Date.now() - at.getTime()) / 60000);
  if (mins < 1) return 'Checked just now';
  if (mins < 60) return `Checked ${mins} min ago`;
  const hours = Math.round(mins / 60);
  if (hours < 24) return `Checked ${hours}h ago`;
  return `Checked ${Math.round(hours / 24)}d ago`;
}

export function LibraryScreen({ navigation }: Props) {
  const { ready, apiKey, shows, refreshing, lastCheck, error, refresh } = useLibrary();
  const today = todayISO();

  // `refresh` changes identity whenever the library does, so hold it in a ref;
  // the auto-check effects should fire on app activity, not on every edit.
  const refreshRef = useRef(refresh);
  useEffect(() => {
    refreshRef.current = refresh;
  }, [refresh]);

  const maybeRefresh = useCallback(() => {
    if (apiKey && isStale(lastCheck)) void refreshRef.current();
  }, [apiKey, lastCheck]);

  // Check on open.
  useEffect(() => {
    if (ready) maybeRefresh();
  }, [ready, maybeRefresh]);

  // Check again when returning from the background.
  useEffect(() => {
    const sub = AppState.addEventListener('change', (next) => {
      if (next === 'active') maybeRefresh();
    });
    return () => sub.remove();
  }, [maybeRefresh]);

  if (!ready) {
    return (
      <View style={styles.centered}>
        <ActivityIndicator color={colors.accent} />
      </View>
    );
  }

  if (!apiKey) {
    return (
      <View style={styles.centered}>
        <Text style={styles.emptyTitle}>Connect to TMDB</Text>
        <Text style={styles.emptyBody}>
          Show Tracker needs a free TMDB API key to look up seasons and air dates.
        </Text>
        <Pressable style={styles.cta} onPress={() => navigation.navigate('Settings')}>
          <Text style={styles.ctaText}>Add API key</Text>
        </Pressable>
      </View>
    );
  }

  const ordered = sortLibrary(shows, today);
  const catchUpCount = ordered.filter((s) => isBehind(s, today)).length;

  return (
    <View style={styles.container}>
      {error && (
        <View style={styles.errorBar}>
          <Text style={styles.errorText}>{error}</Text>
        </View>
      )}

      <FlatList
        data={ordered}
        keyExtractor={(item) => String(item.id)}
        contentContainerStyle={ordered.length === 0 ? styles.emptyList : styles.list}
        refreshControl={
          <RefreshControl
            refreshing={refreshing}
            onRefresh={() => void refresh()}
            tintColor={colors.accent}
            colors={[colors.accent]}
            progressBackgroundColor={colors.surface}
          />
        }
        ListHeaderComponent={
          ordered.length > 0 ? (
            <View style={styles.header}>
              <Text style={styles.headline}>
                {catchUpCount === 0
                  ? 'All caught up'
                  : `${catchUpCount} show${catchUpCount === 1 ? '' : 's'} to catch up on`}
              </Text>
              <Text style={styles.meta}>{formatLastCheck(lastCheck)}</Text>
            </View>
          ) : null
        }
        renderItem={({ item }) => (
          <ShowRow
            show={item}
            today={today}
            onPress={() => navigation.navigate('Detail', { id: item.id })}
          />
        )}
        ItemSeparatorComponent={() => <View style={{ height: spacing.sm }} />}
        ListEmptyComponent={
          <View style={styles.centered}>
            <Text style={styles.emptyTitle}>No shows yet</Text>
            <Text style={styles.emptyBody}>
              Add the shows you follow and this list will tell you when a new season lands.
            </Text>
            <Pressable style={styles.cta} onPress={() => navigation.navigate('Search')}>
              <Text style={styles.ctaText}>Find a show</Text>
            </Pressable>
          </View>
        }
      />

      <Pressable style={styles.fab} onPress={() => navigation.navigate('Search')}>
        <Text style={styles.fabText}>+</Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.bg,
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
    gap: spacing.md,
    backgroundColor: colors.bg,
  },
  list: {
    padding: spacing.lg,
  },
  emptyList: {
    flexGrow: 1,
  },
  header: {
    marginBottom: spacing.md,
    gap: 2,
  },
  headline: {
    color: colors.text,
    fontSize: 18,
    fontWeight: '700',
  },
  meta: {
    color: colors.textFaint,
    fontSize: 12,
  },
  emptyTitle: {
    color: colors.text,
    fontSize: 20,
    fontWeight: '700',
  },
  emptyBody: {
    color: colors.textMuted,
    fontSize: 14,
    textAlign: 'center',
    lineHeight: 20,
  },
  cta: {
    marginTop: spacing.sm,
    backgroundColor: colors.accent,
    paddingHorizontal: spacing.xl,
    paddingVertical: spacing.md,
    borderRadius: radius.md,
  },
  ctaText: {
    color: '#fff',
    fontWeight: '700',
    fontSize: 15,
  },
  errorBar: {
    backgroundColor: '#3A1F22',
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.sm,
  },
  errorText: {
    color: colors.danger,
    fontSize: 13,
  },
  fab: {
    position: 'absolute',
    right: spacing.lg,
    bottom: spacing.xl,
    width: 56,
    height: 56,
    borderRadius: 28,
    backgroundColor: colors.accent,
    alignItems: 'center',
    justifyContent: 'center',
    elevation: 6,
  },
  fabText: {
    color: '#fff',
    fontSize: 30,
    lineHeight: 34,
    fontWeight: '400',
  },
});
