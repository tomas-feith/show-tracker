import { Platform } from 'react-native';
import type { Discovery } from '../core/refresh';
import { isExpoGo } from './environment';

const CHANNEL_ID = 'new-seasons';

type NotificationsModule = typeof import('expo-notifications');

/**
 * Load expo-notifications only where it works.
 *
 * A static import would run at module load and crash Expo Go outright, so this
 * stays a lazy require. Every caller must tolerate null: in Expo Go the app is
 * fully usable, it just cannot post notifications.
 */
function loadNotifications(): NotificationsModule | null {
  if (isExpoGo) return null;
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  return require('expo-notifications') as NotificationsModule;
}

/** True when this build can post notifications at all. */
export const notificationsSupported = !isExpoGo;

/**
 * Show notifications even while the app is foregrounded. Called once at
 * startup rather than at import, so the module stays side-effect free.
 */
export function configureNotifications(): void {
  const Notifications = loadNotifications();
  if (!Notifications) return;

  Notifications.setNotificationHandler({
    handleNotification: async () => ({
      shouldShowBanner: true,
      shouldShowList: true,
      shouldPlaySound: false,
      shouldSetBadge: false,
    }),
  });
}

/**
 * Ask for notification permission and create the Android channel.
 * Returns whether we may actually post notifications.
 */
export async function ensureNotificationPermission(): Promise<boolean> {
  const Notifications = loadNotifications();
  if (!Notifications) return false;

  if (Platform.OS === 'android') {
    await Notifications.setNotificationChannelAsync(CHANNEL_ID, {
      name: 'New seasons',
      importance: Notifications.AndroidImportance.DEFAULT,
      vibrationPattern: [0, 250, 250, 250],
    });
  }

  const current = await Notifications.getPermissionsAsync();
  if (current.granted) return true;
  if (!current.canAskAgain) return false;

  const asked = await Notifications.requestPermissionsAsync();
  return asked.granted;
}

/**
 * Announce newly found seasons. Several at once are collapsed into a single
 * notification, because a batch of five separate alerts is noise, not news.
 */
export async function notifyDiscoveries(discoveries: Discovery[]): Promise<void> {
  if (discoveries.length === 0) return;

  const Notifications = loadNotifications();
  if (!Notifications) return;

  const permitted = await ensureNotificationPermission();
  if (!permitted) return;

  const first = discoveries[0];
  const title =
    discoveries.length === 1
      ? `${first.show.name} - new season`
      : `${discoveries.length} shows have new seasons`;

  const body =
    discoveries.length === 1
      ? `Season ${first.season.seasonNumber} is out.`
      : discoveries
          .slice(0, 4)
          .map((d) => `${d.show.name} S${d.season.seasonNumber}`)
          .join(', ') + (discoveries.length > 4 ? ', and more' : '');

  await Notifications.scheduleNotificationAsync({
    content: {
      title,
      body,
      data: { showIds: discoveries.map((d) => d.show.id) },
    },
    trigger: null, // deliver immediately
  });
}
