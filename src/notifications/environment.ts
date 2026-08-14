import Constants, { ExecutionEnvironment } from 'expo-constants';

/**
 * Whether the app is running inside the Expo Go sandbox rather than its own
 * build.
 *
 * Expo Go dropped the notification native module in SDK 53, and merely
 * *importing* expo-notifications there registers a push-token listener that
 * throws, taking the whole app down before the first render. So the import has
 * to be avoided entirely, not just the calls - hence the lazy `require` in
 * `notify.ts` rather than a top-level import.
 */
export const isExpoGo = Constants.executionEnvironment === ExecutionEnvironment.StoreClient;
