import React from 'react';
import { Image, StyleSheet, Text, View } from 'react-native';
import { posterUrl } from '../api/tmdb';
import { colors, radius } from '../theme';

type Props = {
  path: string | null;
  name: string;
  width: number;
};

/** Poster art with a lettered placeholder for shows TMDB has no image for. */
export function Poster({ path, name, width }: Props) {
  const height = Math.round(width * 1.5);
  const url = posterUrl(path, width > 200 ? 'w500' : 'w185');

  if (!url) {
    return (
      <View style={[styles.placeholder, { width, height }]}>
        <Text style={styles.placeholderText}>{name.slice(0, 1).toUpperCase()}</Text>
      </View>
    );
  }

  return <Image source={{ uri: url }} style={[styles.image, { width, height }]} resizeMode="cover" />;
}

const styles = StyleSheet.create({
  image: {
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceAlt,
  },
  placeholder: {
    borderRadius: radius.sm,
    backgroundColor: colors.surfaceAlt,
    alignItems: 'center',
    justifyContent: 'center',
  },
  placeholderText: {
    color: colors.textFaint,
    fontSize: 24,
    fontWeight: '700',
  },
});
