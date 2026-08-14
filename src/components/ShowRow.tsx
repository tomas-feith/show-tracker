import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { describeDays, formatEpisode, showState } from '../core/newness';
import type { ShowState, TrackedShow } from '../core/types';
import { colors, radius, spacing } from '../theme';
import { Poster } from './Poster';

/** One line of status text plus the colour that carries its urgency. */
function describeState(state: ShowState): { label: string; color: string } {
  switch (state.kind) {
    case 'new_season':
      return {
        label: `Season ${state.season.seasonNumber} out ${describeDays(state.daysAgo, 'ago')}`,
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
  const isNew = state.kind === 'new_season';

  return (
    <Pressable
      onPress={onPress}
      style={({ pressed }) => [styles.row, isNew && styles.rowNew, pressed && styles.pressed]}
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
      {isNew && (
        <View style={styles.badge}>
          <Text style={styles.badgeText}>NEW</Text>
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
