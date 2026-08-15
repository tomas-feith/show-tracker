import type { TrackedShow } from '../../core/types';
import {
  EXPORT_FORMAT,
  EXPORT_VERSION,
  buildExport,
  exportFileName,
  serializeExport,
} from '../export';
import { migrateShows } from '../store';

const show: TrackedShow = {
  id: 1396,
  name: 'Breaking Bad',
  posterPath: '/poster.jpg',
  firstAirDate: '2008-01-20',
  status: 'Ended',
  seasons: [
    { seasonNumber: 0, name: 'Specials', airDate: '2009-02-17', episodeCount: 6 },
    { seasonNumber: 1, name: 'Season 1', airDate: '2008-01-20', episodeCount: 7 },
    { seasonNumber: 2, name: 'Season 2', airDate: '2009-03-08', episodeCount: 13 },
  ],
  lastEpisode: {
    seasonNumber: 2,
    episodeNumber: 13,
    name: 'ABQ',
    airDate: '2009-05-31',
  },
  nextEpisode: null,
  watchedThroughSeason: 1,
  knownAiredSeason: 2,
  addedAt: '2026-01-05T10:00:00.000Z',
  lastCheckedAt: '2026-08-14T09:30:00.000Z',
};

const now = new Date('2026-08-15T18:45:12.000Z');

describe('buildExport', () => {
  it('stamps the format and version so the importer can identify the file', () => {
    const payload = buildExport([show], null, now);
    expect(payload.format).toBe(EXPORT_FORMAT);
    expect(payload.version).toBe(EXPORT_VERSION);
  });

  it('records when the export was taken and carries the last check across', () => {
    const payload = buildExport([show], '2026-08-14T09:30:00.000Z', now);
    expect(payload.exportedAt).toBe('2026-08-15T18:45:12.000Z');
    expect(payload.lastCheckedAt).toBe('2026-08-14T09:30:00.000Z');
  });

  it('tolerates a library that has never been refreshed', () => {
    expect(buildExport([show], null, now).lastCheckedAt).toBeNull();
  });

  it('exports an empty library rather than refusing', () => {
    const payload = buildExport([], null, now);
    expect(payload.shows).toEqual([]);
  });

  it('never carries the TMDB key, which is a credential and not library data', () => {
    const text = serializeExport(buildExport([show], null, now));
    expect(text).not.toMatch(/tmdb.?key/i);
    expect(text).not.toMatch(/api.?key/i);
  });
});

describe('serializeExport', () => {
  it('round-trips a library without losing or altering a single show', () => {
    const restored = JSON.parse(serializeExport(buildExport([show], null, now)));
    expect(restored.shows).toEqual([show]);
  });

  it('preserves both watermarks separately, including a deliberate zero', () => {
    // The whole point of the two-watermark design: collapsing them here would
    // reintroduce on the other side exactly the bug the split exists to avoid.
    const unstarted = { ...show, watchedThroughSeason: 0, knownAiredSeason: 4 };
    const restored = JSON.parse(serializeExport(buildExport([unstarted], null, now)));
    expect(restored.shows[0].watchedThroughSeason).toBe(0);
    expect(restored.shows[0].knownAiredSeason).toBe(4);
  });

  it('keeps season 0 in the payload rather than filtering it out', () => {
    // Specials are excluded when deciding what has aired, not when storing.
    // Dropping them here would make an export lossy against the source data.
    const restored = JSON.parse(serializeExport(buildExport([show], null, now)));
    expect(restored.shows[0].seasons).toHaveLength(3);
    expect(restored.shows[0].seasons[0].seasonNumber).toBe(0);
  });

  it('survives the migration the importer will run on the far side', () => {
    // The Kotlin importer applies the same normalisation as loadShows, so an
    // export fed back through it must come out unchanged.
    const restored = JSON.parse(serializeExport(buildExport([show], null, now)));
    expect(migrateShows(restored.shows)).toEqual([show]);
  });

  it('is valid JSON with no undefined values smuggled in', () => {
    expect(() => JSON.parse(serializeExport(buildExport([show], null, now)))).not.toThrow();
  });
});

describe('exportFileName', () => {
  it('names the file by local calendar date, sortable and safe for a share sheet', () => {
    // Constructed in local time: the filename should match the day the user
    // thinks it is, whatever their offset from UTC.
    const local = new Date(2026, 7, 15, 18, 45);
    expect(exportFileName(local)).toBe('show-tracker-2026-08-15.json');
  });

  it('pads single-digit months and days', () => {
    expect(exportFileName(new Date(2026, 0, 9, 12, 0))).toBe('show-tracker-2026-01-09.json');
  });
});
