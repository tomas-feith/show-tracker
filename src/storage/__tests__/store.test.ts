import { migrateShows } from '../store';

describe('migrateShows', () => {
  const legacy = {
    id: 42,
    name: 'Old Install Show',
    posterPath: null,
    firstAirDate: '2019-01-01',
    status: 'Returning Series',
    seasons: [{ seasonNumber: 1, name: 'Season 1', airDate: '2019-01-01', episodeCount: 10 }],
    lastEpisode: null,
    nextEpisode: null,
    acknowledgedSeason: 3,
    addedAt: '2025-01-01T00:00:00.000Z',
    lastCheckedAt: null,
  };

  it('splits the old single watermark into both of the new ones', () => {
    const [show] = migrateShows([legacy]);
    expect(show.watchedThroughSeason).toBe(3);
    expect(show.knownAiredSeason).toBe(3);
  });

  it('drops the obsolete field rather than carrying it forward', () => {
    const [show] = migrateShows([legacy]);
    expect(show).not.toHaveProperty('acknowledgedSeason');
  });

  it('defaults both watermarks to zero when neither field exists', () => {
    const { acknowledgedSeason: _omitted, ...bare } = legacy;
    const [show] = migrateShows([bare]);
    expect(show.watchedThroughSeason).toBe(0);
    expect(show.knownAiredSeason).toBe(0);
  });

  it('leaves an already-migrated show untouched', () => {
    const current = { ...legacy, watchedThroughSeason: 1, knownAiredSeason: 5 };
    const [show] = migrateShows([current]);
    expect(show.watchedThroughSeason).toBe(1);
    expect(show.knownAiredSeason).toBe(5);
  });

  it('preserves a deliberate zero rather than treating it as missing', () => {
    // ?? rather than ||, so "not started" survives the upgrade.
    const current = { ...legacy, watchedThroughSeason: 0, knownAiredSeason: 4 };
    const [show] = migrateShows([current]);
    expect(show.watchedThroughSeason).toBe(0);
    expect(show.knownAiredSeason).toBe(4);
  });

  it('rejects payloads that are not arrays of shows', () => {
    expect(migrateShows(null)).toEqual([]);
    expect(migrateShows({ shows: [] })).toEqual([]);
    expect(migrateShows([{ junk: true }, null])).toEqual([]);
  });

  it('repairs a show whose season list went missing', () => {
    const broken = { ...legacy, seasons: undefined };
    expect(migrateShows([broken])[0].seasons).toEqual([]);
  });
});
