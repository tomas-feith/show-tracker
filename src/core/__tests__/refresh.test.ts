import { findDiscovery, mergeShow } from '../refresh';
import type { Season, ShowDetail, TrackedShow } from '../types';

const TODAY = '2026-08-14';

function season(seasonNumber: number, airDate: string | null, episodeCount = 10): Season {
  return { seasonNumber, name: `Season ${seasonNumber}`, airDate, episodeCount };
}

function show(overrides: Partial<TrackedShow> = {}): TrackedShow {
  return {
    id: 1,
    name: 'Test Show',
    posterPath: null,
    firstAirDate: '2019-01-01',
    status: 'Returning Series',
    seasons: [],
    lastEpisode: null,
    nextEpisode: null,
    acknowledgedSeason: 0,
    addedAt: '2026-01-01T00:00:00.000Z',
    lastCheckedAt: null,
    ...overrides,
  };
}

function detail(overrides: Partial<ShowDetail> = {}): ShowDetail {
  return {
    id: 1,
    name: 'Test Show',
    overview: 'An overview.',
    posterPath: '/p.jpg',
    firstAirDate: '2019-01-01',
    status: 'Returning Series',
    seasons: [],
    lastEpisode: null,
    nextEpisode: null,
    ...overrides,
  };
}

describe('mergeShow', () => {
  it('preserves the user watermark and added date', () => {
    const existing = show({ acknowledgedSeason: 4, addedAt: '2025-05-05T00:00:00.000Z' });
    const merged = mergeShow(existing, detail({ seasons: [season(5, '2026-01-01')] }));
    expect(merged.acknowledgedSeason).toBe(4);
    expect(merged.addedAt).toBe('2025-05-05T00:00:00.000Z');
  });

  it('takes fresh metadata from TMDB', () => {
    const merged = mergeShow(
      show({ name: 'Old Name', status: 'Returning Series' }),
      detail({ name: 'New Name', status: 'Ended', seasons: [season(1, '2020-01-01')] }),
      new Date('2026-08-14T10:00:00.000Z')
    );
    expect(merged.name).toBe('New Name');
    expect(merged.status).toBe('Ended');
    expect(merged.seasons).toHaveLength(1);
    expect(merged.lastCheckedAt).toBe('2026-08-14T10:00:00.000Z');
  });
});

describe('findDiscovery', () => {
  it('announces a season that appeared since the last check', () => {
    const before = show({ seasons: [season(1, '2020-01-01')], acknowledgedSeason: 1 });
    const after = { ...before, seasons: [season(1, '2020-01-01'), season(2, '2026-08-01')] };
    expect(findDiscovery(before, after, TODAY)?.seasonNumber).toBe(2);
  });

  it('stays quiet when nothing changed, even with an unseen season', () => {
    // The user has been told already and has not marked it seen; re-announcing
    // on every refresh would be spam.
    const before = show({
      seasons: [season(1, '2020-01-01'), season(2, '2026-08-01')],
      acknowledgedSeason: 1,
    });
    expect(findDiscovery(before, { ...before }, TODAY)).toBeNull();
  });

  it('stays quiet when the new season is already acknowledged', () => {
    const before = show({ seasons: [season(1, '2020-01-01')], acknowledgedSeason: 2 });
    const after = { ...before, seasons: [season(1, '2020-01-01'), season(2, '2026-08-01')] };
    expect(findDiscovery(before, after, TODAY)).toBeNull();
  });

  it('stays quiet when the newly listed season has not aired yet', () => {
    const before = show({ seasons: [season(1, '2020-01-01')], acknowledgedSeason: 1 });
    const after = { ...before, seasons: [season(1, '2020-01-01'), season(2, '2027-01-01')] };
    expect(findDiscovery(before, after, TODAY)).toBeNull();
  });

  it('announces a season that crossed its air date without any data change', () => {
    // The season was already listed; today it simply became watchable.
    const before = show({
      seasons: [season(1, '2020-01-01'), season(2, TODAY)],
      acknowledgedSeason: 1,
    });
    // Yesterday season 2 had not aired, so `before` evaluated at yesterday's
    // date had a latest of season 1; today it rises to season 2.
    const yesterdayView = { ...before, seasons: [season(1, '2020-01-01')] };
    expect(findDiscovery(yesterdayView, before, TODAY)?.seasonNumber).toBe(2);
  });

  it('announces the first ever aired season of a newly started show', () => {
    const before = show({ seasons: [season(1, '2027-01-01')], acknowledgedSeason: 0 });
    const after = { ...before, seasons: [season(1, '2026-08-01')] };
    expect(findDiscovery(before, after, TODAY)?.seasonNumber).toBe(1);
  });
});
