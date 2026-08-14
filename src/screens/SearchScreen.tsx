import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import React, { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { searchShows } from '../api/tmdb';
import { Poster } from '../components/Poster';
import type { SearchResult } from '../core/types';
import type { RootStackParamList } from '../navigation/types';
import { useLibrary } from '../state/LibraryContext';
import { colors, radius, spacing } from '../theme';

type Props = NativeStackScreenProps<RootStackParamList, 'Search'>;

const DEBOUNCE_MS = 350;

export function SearchScreen({ navigation }: Props) {
  const { apiKey, isTracked, addShow } = useLibrary();
  const [query, setQuery] = useState('');
  // Results carry the query they belong to, so a stale list is simply not
  // rendered rather than needing to be cleared from an effect.
  const [results, setResults] = useState<{ query: string; items: SearchResult[] }>({
    query: '',
    items: [],
  });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [adding, setAdding] = useState<number | null>(null);

  // Guards against a slow early request overwriting a newer one's results.
  const requestId = useRef(0);

  const trimmed = query.trim();
  const tooShort = trimmed.length < 2;
  const visible = results.query === trimmed ? results.items : [];

  useEffect(() => {
    if (!apiKey || tooShort) return;

    const id = ++requestId.current;
    const timer = setTimeout(async () => {
      setLoading(true);
      try {
        const found = await searchShows(apiKey, trimmed);
        if (id !== requestId.current) return;
        setResults({ query: trimmed, items: found });
        setError(null);
      } catch (err) {
        if (id !== requestId.current) return;
        setError(err instanceof Error ? err.message : 'Search failed.');
        setResults({ query: trimmed, items: [] });
      } finally {
        if (id === requestId.current) setLoading(false);
      }
    }, DEBOUNCE_MS);

    return () => clearTimeout(timer);
  }, [trimmed, tooShort, apiKey]);

  async function onAdd(item: SearchResult) {
    setAdding(item.id);
    try {
      await addShow(item.id);
      setError(null);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Could not add show.');
    } finally {
      setAdding(null);
    }
  }

  return (
    <View style={styles.container}>
      <TextInput
        value={query}
        onChangeText={setQuery}
        placeholder="Search for a show"
        placeholderTextColor={colors.textFaint}
        style={styles.input}
        autoFocus
        autoCorrect={false}
        returnKeyType="search"
      />

      {error && <Text style={styles.error}>{error}</Text>}

      {loading && visible.length === 0 ? (
        <ActivityIndicator style={styles.loader} color={colors.accent} />
      ) : (
        <FlatList
          data={visible}
          keyExtractor={(item) => String(item.id)}
          keyboardShouldPersistTaps="handled"
          contentContainerStyle={styles.list}
          ItemSeparatorComponent={() => <View style={{ height: spacing.sm }} />}
          ListEmptyComponent={
            !tooShort && !loading ? (
              <Text style={styles.hint}>No shows matched &ldquo;{trimmed}&rdquo;.</Text>
            ) : (
              <Text style={styles.hint}>Type at least two characters.</Text>
            )
          }
          renderItem={({ item }) => {
            const tracked = isTracked(item.id);
            const year = item.firstAirDate ? item.firstAirDate.slice(0, 4) : '--';
            return (
              <View style={styles.row}>
                <Pressable
                  style={styles.rowMain}
                  onPress={() => navigation.navigate('Detail', { id: item.id })}
                >
                  <Poster path={item.posterPath} name={item.name} width={44} />
                  <View style={styles.rowBody}>
                    <Text style={styles.name} numberOfLines={1}>
                      {item.name}
                    </Text>
                    <Text style={styles.year}>{year}</Text>
                  </View>
                </Pressable>

                <Pressable
                  disabled={tracked || adding === item.id}
                  onPress={() => void onAdd(item)}
                  style={[styles.addBtn, tracked && styles.addBtnDone]}
                >
                  {adding === item.id ? (
                    <ActivityIndicator size="small" color={colors.text} />
                  ) : (
                    <Text style={styles.addText}>{tracked ? 'Added' : 'Follow'}</Text>
                  )}
                </Pressable>
              </View>
            );
          }}
        />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: colors.bg,
    padding: spacing.lg,
  },
  input: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.md,
    color: colors.text,
    fontSize: 16,
  },
  loader: {
    marginTop: spacing.xl,
  },
  list: {
    paddingTop: spacing.lg,
  },
  hint: {
    color: colors.textFaint,
    fontSize: 13,
    textAlign: 'center',
    marginTop: spacing.xl,
  },
  error: {
    color: colors.danger,
    fontSize: 13,
    marginTop: spacing.sm,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
    backgroundColor: colors.surface,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: colors.border,
    padding: spacing.md,
  },
  rowMain: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  rowBody: {
    flex: 1,
    gap: 2,
  },
  name: {
    color: colors.text,
    fontSize: 15,
    fontWeight: '600',
  },
  year: {
    color: colors.textMuted,
    fontSize: 12,
  },
  addBtn: {
    backgroundColor: colors.accent,
    borderRadius: radius.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    minWidth: 74,
    alignItems: 'center',
  },
  addBtnDone: {
    backgroundColor: colors.surfaceAlt,
  },
  addText: {
    color: colors.text,
    fontWeight: '700',
    fontSize: 13,
  },
});
