import { refreshShows } from '../core/refresh';
import { loadApiKey, loadShows, saveLastCheck, saveShows } from '../storage/store';
import { isExpoGo } from './environment';
import { notifyDiscoveries } from './notify';

export const BACKGROUND_CHECK_TASK = 'showtracker-background-check';

/** Roughly twice a day. Android's WorkManager treats this as a floor, not a promise. */
const INTERVAL_MINUTES = 12 * 60;

type TaskManagerModule = typeof import('expo-task-manager');
type BackgroundTaskModule = typeof import('expo-background-task');

/**
 * Background execution is unavailable in Expo Go, so both modules are required
 * lazily and every caller tolerates null. Loading them unconditionally would
 * make the development client unusable for the sake of a feature it cannot run.
 */
function loadModules(): { TaskManager: TaskManagerModule; BackgroundTask: BackgroundTaskModule } | null {
  if (isExpoGo) return null;
  /* eslint-disable @typescript-eslint/no-require-imports */
  return {
    TaskManager: require('expo-task-manager') as TaskManagerModule,
    BackgroundTask: require('expo-background-task') as BackgroundTaskModule,
  };
  /* eslint-enable @typescript-eslint/no-require-imports */
}

/** True when this build can run the periodic background check. */
export const backgroundChecksSupported = !isExpoGo;

/**
 * The device-side check. This is what makes the app useful without a server:
 * Android wakes it periodically, it re-reads TMDB, and it posts a local
 * notification if anything dropped.
 *
 * Registered at import time in real builds because TaskManager must know the
 * task before the OS can hand work to it, including on a cold start straight
 * into the background.
 */
const modules = loadModules();

if (modules) {
  const { TaskManager, BackgroundTask } = modules;

  TaskManager.defineTask(BACKGROUND_CHECK_TASK, async () => {
    try {
      const apiKey = await loadApiKey();
      if (!apiKey) return BackgroundTask.BackgroundTaskResult.Success;

      const shows = await loadShows();
      if (shows.length === 0) return BackgroundTask.BackgroundTaskResult.Success;

      const outcome = await refreshShows(apiKey, shows);
      await saveShows(outcome.shows);
      await saveLastCheck(new Date().toISOString());
      await notifyDiscoveries(outcome.discoveries);

      return BackgroundTask.BackgroundTaskResult.Success;
    } catch {
      // Never throw out of a background task: Android counts crashes against us
      // and will back off scheduling.
      return BackgroundTask.BackgroundTaskResult.Failed;
    }
  });
}

/** Register the periodic check. Safe to call on every launch. */
export async function registerBackgroundCheck(): Promise<void> {
  if (!modules) return;
  const { TaskManager, BackgroundTask } = modules;

  const status = await BackgroundTask.getStatusAsync();
  if (status === BackgroundTask.BackgroundTaskStatus.Restricted) return;

  const already = await TaskManager.isTaskRegisteredAsync(BACKGROUND_CHECK_TASK);
  if (already) return;

  await BackgroundTask.registerTaskAsync(BACKGROUND_CHECK_TASK, {
    minimumInterval: INTERVAL_MINUTES,
  });
}
