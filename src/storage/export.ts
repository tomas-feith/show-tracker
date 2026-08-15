import { todayISO } from '../core/newness';
import type { TrackedShow } from '../core/types';

/**
 * Identifies the payload as ours. Checked on import, so that picking the wrong
 * file from the document picker fails with a clear message rather than
 * half-populating a library from arbitrary JSON.
 */
export const EXPORT_FORMAT = 'showtracker-export';

/**
 * Bumped whenever the shape below changes incompatibly. The importer reads
 * this and refuses anything newer than it understands: a forward-compatible
 * guess would silently drop whatever field was added.
 */
export const EXPORT_VERSION = 1;

/**
 * The transferable form of a library.
 *
 * Deliberately NOT included: the TMDB API key. It is a credential, and an
 * export is meant to leave the device - through a share sheet, into Drive, or
 * onto a desktop - where a plaintext key would outlive any control over it.
 * Re-pasting the key on the other side takes seconds and is a much better
 * trade than a secret sitting in a backup folder.
 */
export type ExportPayload = {
  format: typeof EXPORT_FORMAT;
  version: number;
  /** ISO timestamp of when the export was taken. Informational. */
  exportedAt: string;
  /** Carried over so a restored library does not immediately look stale. */
  lastCheckedAt: string | null;
  shows: TrackedShow[];
};

/**
 * Build the payload. Pure, so the exact bytes that will reach the other app
 * can be asserted in a test rather than inspected by hand on a phone.
 *
 * Shows are written through as-is. They have already been through
 * `migrateShows` on load, so what is in memory is current-shape by
 * construction, and re-normalising here would only add a second definition of
 * the shape that could drift from the first.
 */
export function buildExport(
  shows: TrackedShow[],
  lastCheckedAt: string | null,
  now: Date = new Date()
): ExportPayload {
  return {
    format: EXPORT_FORMAT,
    version: EXPORT_VERSION,
    exportedAt: now.toISOString(),
    lastCheckedAt,
    shows,
  };
}

/**
 * Indented rather than minified. An export is a backup a person may end up
 * opening in a text editor to check it is not empty, and the size difference
 * is irrelevant for a library of tens of shows.
 */
export function serializeExport(payload: ExportPayload): string {
  return JSON.stringify(payload, null, 2);
}

/**
 * A filename that sorts chronologically and survives a share sheet.
 *
 * Uses the local calendar date, matching what the user would call "today";
 * `exportedAt` inside the payload keeps the precise instant.
 */
export function exportFileName(now: Date = new Date()): string {
  return `show-tracker-${todayISO(now)}.json`;
}
