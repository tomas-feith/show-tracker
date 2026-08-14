import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import React, { useState } from 'react';
import {
  ActivityIndicator,
  Linking,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { verifyKey } from '../api/tmdb';
import type { RootStackParamList } from '../navigation/types';
import { ensureNotificationPermission } from '../notifications/notify';
import { useLibrary } from '../state/LibraryContext';
import { colors, radius, spacing } from '../theme';

type Props = NativeStackScreenProps<RootStackParamList, 'Settings'>;

const SIGNUP_URL = 'https://www.themoviedb.org/settings/api';

export function SettingsScreen({ navigation }: Props) {
  const { apiKey, setApiKey, forgetApiKey, shows } = useLibrary();
  const [draft, setDraft] = useState('');
  const [checking, setChecking] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  async function onSave() {
    const key = draft.trim();
    if (!key) return;

    setChecking(true);
    setMessage(null);
    try {
      // Validate before storing, so a typo surfaces here rather than as a
      // mysterious empty library later.
      await verifyKey(key);
      await setApiKey(key);
      await ensureNotificationPermission();
      setDraft('');
      setFailed(false);
      setMessage('Key saved.');
      navigation.goBack();
    } catch (err) {
      setFailed(true);
      setMessage(err instanceof Error ? err.message : 'Could not verify key.');
    } finally {
      setChecking(false);
    }
  }

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <Text style={styles.sectionTitle}>TMDB API key</Text>
      <Text style={styles.body}>
        Season data comes from The Movie Database. A personal key is free: create an account, open
        Settings then API, and request a Developer key.
      </Text>

      <Pressable onPress={() => void Linking.openURL(SIGNUP_URL)}>
        <Text style={styles.link}>Open TMDB API settings</Text>
      </Pressable>

      <View style={styles.statusRow}>
        <Text style={styles.body}>Status:</Text>
        <Text style={[styles.body, { color: apiKey ? colors.airing : colors.danger }]}>
          {apiKey ? 'Connected' : 'Not configured'}
        </Text>
      </View>

      <TextInput
        value={draft}
        onChangeText={setDraft}
        placeholder={apiKey ? 'Replace key' : 'Paste your API key or read token'}
        placeholderTextColor={colors.textFaint}
        style={styles.input}
        autoCapitalize="none"
        autoCorrect={false}
        multiline
      />

      <Pressable style={styles.primaryBtn} disabled={checking || !draft.trim()} onPress={() => void onSave()}>
        {checking ? (
          <ActivityIndicator size="small" color="#fff" />
        ) : (
          <Text style={styles.primaryText}>Verify and save</Text>
        )}
      </Pressable>

      {message && (
        <Text style={[styles.body, { color: failed ? colors.danger : colors.airing }]}>{message}</Text>
      )}

      {apiKey && (
        <Pressable style={styles.secondaryBtn} onPress={() => void forgetApiKey()}>
          <Text style={styles.secondaryText}>Remove stored key</Text>
        </Pressable>
      )}

      <View style={styles.divider} />

      <Text style={styles.sectionTitle}>Notifications</Text>
      <Text style={styles.body}>
        The app checks for new seasons roughly twice a day in the background and again whenever you
        open it. Android decides exactly when background checks run, so treat them as a bonus rather
        than a guarantee.
      </Text>
      <Pressable style={styles.secondaryBtn} onPress={() => void ensureNotificationPermission()}>
        <Text style={styles.secondaryText}>Grant notification permission</Text>
      </Pressable>

      <View style={styles.divider} />
      <Text style={styles.faint}>Following {shows.length} show{shows.length === 1 ? '' : 's'}.</Text>
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
    gap: spacing.md,
  },
  sectionTitle: {
    color: colors.text,
    fontSize: 17,
    fontWeight: '700',
  },
  body: {
    color: colors.textMuted,
    fontSize: 14,
    lineHeight: 20,
  },
  faint: {
    color: colors.textFaint,
    fontSize: 12,
  },
  link: {
    color: colors.accent,
    fontSize: 14,
    fontWeight: '600',
  },
  statusRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  input: {
    backgroundColor: colors.surface,
    borderWidth: 1,
    borderColor: colors.border,
    borderRadius: radius.md,
    padding: spacing.md,
    color: colors.text,
    fontSize: 14,
    minHeight: 56,
  },
  primaryBtn: {
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
    backgroundColor: colors.surfaceAlt,
    borderRadius: radius.md,
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  secondaryText: {
    color: colors.textMuted,
    fontWeight: '600',
    fontSize: 14,
  },
  divider: {
    height: 1,
    backgroundColor: colors.border,
    marginVertical: spacing.sm,
  },
});
