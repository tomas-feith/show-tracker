import { DarkTheme, NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { StatusBar } from 'expo-status-bar';
import React, { useEffect } from 'react';
import { Pressable, StyleSheet, Text } from 'react-native';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import type { RootStackParamList } from './src/navigation/types';
// Importing for the side effect of defining the background task before the OS
// can hand us work.
import { registerBackgroundCheck } from './src/notifications/backgroundTask';
import { DetailScreen } from './src/screens/DetailScreen';
import { LibraryScreen } from './src/screens/LibraryScreen';
import { SearchScreen } from './src/screens/SearchScreen';
import { SettingsScreen } from './src/screens/SettingsScreen';
import { LibraryProvider } from './src/state/LibraryContext';
import { colors } from './src/theme';

const Stack = createNativeStackNavigator<RootStackParamList>();

const navTheme = {
  ...DarkTheme,
  colors: {
    ...DarkTheme.colors,
    background: colors.bg,
    card: colors.surface,
    text: colors.text,
    border: colors.border,
    primary: colors.accent,
  },
};

export default function App() {
  useEffect(() => {
    // Background scheduling is best-effort; a device that refuses it still has
    // the on-open check, so failures here are not worth surfacing.
    registerBackgroundCheck().catch(() => {});
  }, []);

  return (
    <SafeAreaProvider>
      <LibraryProvider>
        <StatusBar style="light" />
        <NavigationContainer theme={navTheme}>
          <Stack.Navigator
            screenOptions={{
              headerStyle: { backgroundColor: colors.surface },
              headerTintColor: colors.text,
              headerTitleStyle: { fontWeight: '700' },
              contentStyle: { backgroundColor: colors.bg },
            }}
          >
            <Stack.Screen
              name="Library"
              component={LibraryScreen}
              options={({ navigation }) => ({
                title: 'My Shows',
                headerRight: () => (
                  <Pressable
                    onPress={() => navigation.navigate('Settings')}
                    hitSlop={12}
                    accessibilityLabel="Settings"
                  >
                    <Text style={styles.headerButton}>Settings</Text>
                  </Pressable>
                ),
              })}
            />
            <Stack.Screen name="Search" component={SearchScreen} options={{ title: 'Add a show' }} />
            <Stack.Screen name="Detail" component={DetailScreen} options={{ title: '' }} />
            <Stack.Screen name="Settings" component={SettingsScreen} options={{ title: 'Settings' }} />
          </Stack.Navigator>
        </NavigationContainer>
      </LibraryProvider>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  headerButton: {
    color: colors.accent,
    fontSize: 15,
    fontWeight: '600',
  },
});
