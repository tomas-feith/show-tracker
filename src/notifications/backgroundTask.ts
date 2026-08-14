import * as BackgroundTask from 'expo-background-task';
import * as TaskManager from 'expo-task-manager';
import { refreshShows } from '../core/refresh';
import { loadApiKey, loadShows, saveLastCheck, saveShows } from '../storage/store';
import { notifyDiscoveries } from './notify';

export const BACKGROUND_CHECK_TASK = 'showtracker-background-check';

/** Roughly twice a day. Android's WorkManager treats this as a floor, not a promise. */
const INTERVAL_MINUTES = 12 * 60;

/**
 * The device-side check. This is what makes the app useful without a server:
 * Android wakes it periodically, it re-reads TMDB, and it posts a local
 * notification if anything dropped.
 *
 * Defined at module scope because TaskManager must know the task before the
 * OS can hand work to it, including on a cold start into the background.
 */
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

/** Register the periodic check. Safe to call on every launch. */
export async function registerBackgroundCheck(): Promise<void> {
  const status = await BackgroundTask.getStatusAsync();
  if (status === BackgroundTask.BackgroundTaskStatus.Restricted) return;

  const already = await TaskManager.isTaskRegisteredAsync(BACKGROUND_CHECK_TASK);
  if (already) return;

  await BackgroundTask.registerTaskAsync(BACKGROUND_CHECK_TASK, {
    minimumInterval: INTERVAL_MINUTES,
  });
}
