import { File, Paths } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import type { TrackedShow } from '../core/types';
import { buildExport, exportFileName, serializeExport } from './export';

/**
 * Write the library to a file and hand it to the system share sheet.
 *
 * Written to the cache directory, not the document directory: the share sheet
 * copies the bytes into whatever target the user picks, so the file here is a
 * handover buffer with no reason to outlive the share. Android may reclaim the
 * cache freely, which is exactly the desired lifetime.
 *
 * The file is overwritten rather than uniquely named, so repeated exports on
 * the same day do not quietly accumulate copies of the whole library.
 */
export async function shareExport(
  shows: TrackedShow[],
  lastCheckedAt: string | null,
  now: Date = new Date()
): Promise<void> {
  if (!(await Sharing.isAvailableAsync())) {
    throw new Error('Sharing is not available on this device.');
  }

  const file = new File(Paths.cache, exportFileName(now));
  file.create({ overwrite: true });
  file.write(serializeExport(buildExport(shows, lastCheckedAt, now)));

  await Sharing.shareAsync(file.uri, {
    mimeType: 'application/json',
    dialogTitle: 'Export Show Tracker library',
    UTI: 'public.json',
  });
}
