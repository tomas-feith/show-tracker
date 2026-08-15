# Stack

React Native 0.86 + Expo SDK 57, TypeScript. AsyncStorage for persistence,
React Navigation for routing, expo-notifications + expo-background-task for the
periodic TMDB check.

This is NOT the default for new Android projects here - see the sibling
`habit_tracker`, which is native Kotlin + Jetpack Compose. Expo was chosen for
this app because it is network-and-list shaped (Expo's sweet spot) and Expo Go
allows iterating without a Gradle toolchain. The cost is real: the Expo Go
sandbox dropped the notifications native module in SDK 53, which is why
`src/notifications/` carries lazy `require`s and null-tolerant callers instead
of plain imports.

The `ios` and `web` targets declared in `app.json` are unused. This ships to
Android only.

A port to Kotlin + Compose is under consideration to match `habit_tracker`.
Do not start one without being asked.

# Expo HAS CHANGED

Read the exact versioned docs at https://docs.expo.dev/versions/v57.0.0/ before writing any code.
