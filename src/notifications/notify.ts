import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';
import type { Discovery } from '../core/refresh';

const CHANNEL_ID = 'new-seasons';

/** Show notifications even while the app is foregrounded. */
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});

/**
 * Ask for notification permission and create the Android channel.
 * Returns whether we may actually post notifications.
 */
export async function ensureNotificationPermission(): Promise<boolean> {
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
    content: { title, body, data: { showIds: discoveries.map((d) => d.show.id) } },
    trigger: null, // deliver immediately
  });
}
