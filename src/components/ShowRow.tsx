import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { describeDays, formatEpisode, showState } from '../core/newness';
import type { ShowState, TrackedShow } from '../core/types';
import { colors, radius, spacing } from '../theme';
import { Poster } from './Poster';

/** One line of status text plus the colour that carries its urgency. */
function describeState(state: ShowState): { label: string; color: string } {
  switch (state.kind) {
    case 'behind':
      // One unwatched season reads better as news; several read as a backlog.
      return {
        label:
          state.seasonsBehind === 1
            ? `Season ${state.latest.seasonNumber} out ${describeDays(state.daysAgo, 'ago')}`
            : `${state.seasonsBehind} seasons behind, newest is S${state.latest.seasonNumber}`,
        color: colors.new,
      };
    case 'airing':
      return {
        label: `${formatEpisode(state.next)} ${describeDays(state.daysUntil, 'until')}`,
        color: colors.airing,
      };
    case 'upcoming':
      return {
        label: `Season ${state.season.seasonNumber} ${describeDays(state.daysUntil, 'until')}`,
        color: colors.accent,
      };
    case 'waiting':
      return { label: 'No date announced', color: colors.textMuted };
    case 'ended':
      return { label: 'Ended', color: colors.textFaint };
  }
}

type Props = {
  show: TrackedShow;
  today: string;
  onPress: () => void;
};

export function ShowRow({ show, today, onPress }: Props) {
  const state = showState(show, today);
  const { label, color } = describeState(state);
  const hasBacklog = state.kind === 'behind';

  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [styles.row, hasBacklog && styles.rowNew, pressed && styles.pressed]}
    >
      <Poster path={show.posterPath} name={show.name} width={54} />
      <View style={styles.body}>
        <Text style={styles.name} numberOfLines={1}>
          {show.name}
        </Text>
        <Text style={[styles.status, { color }]} numberOfLines={1}>
          {label}
        </Text>
      </View>
      {state.kind === 'behind' && (
        <View style={styles.badge}>
          <Text style={styles.badgeText}>
            {state.seasonsBehind === 1 ? 'NEW' : `+${state.seasonsBehind}`}
          </Text>
        </View>
      )}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    padding: spacing.md,
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
  },
  rowNew: {
    borderColor: colors.new,
  },
  pressed: {
    opacity: 0.7,
  },
  body: {
    flex: 1,
    gap: 3,
  },
  name: {
    color: colors.text,
    fontSize: 16,
    fontWeight: '600',
  },
  status: {
    fontSize: 13,
  },
  badge: {
    backgroundColor: colors.new,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: 3,
  },
  badgeText: {
    color: '#1A1206',
    fontSize: 11,
    fontWeight: '800',
    letterSpacing: 0.5,
  },
});
