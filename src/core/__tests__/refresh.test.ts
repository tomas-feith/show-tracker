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
    watchedThroughSeason: 0,
    knownAiredSeason: 0,
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
    const existing = show({ watchedThroughSeason: 4, addedAt: '2025-05-05T00:00:00.000Z' });
    const merged = mergeShow(existing, detail({ seasons: [season(5, '2026-01-01')] }));
    expect(merged.watchedThroughSeason).toBe(4);
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
    const before = show({
      seasons: [season(1, '2020-01-01')],
      watchedThroughSeason: 1,
      knownAiredSeason: 1,
    });
    const after = { ...before, seasons: [season(1, '2020-01-01'), season(2, '2026-08-01')] };
    expect(findDiscovery(before, after, TODAY)?.seasonNumber).toBe(2);
  });

  it('announces a season TMDB listed early once its air date arrives', () => {
    // The regression that matters. TMDB announced season 2 months ahead, so it
    // was already in the stored season list with a future date. Today that date
    // passed. Nothing about the data changed - only the calendar did - and this
    // is exactly the "new season dropped and I never heard" case.
    const stored = [season(1, '2020-01-01'), season(2, '2026-08-10')];
    const before = show({ seasons: stored, watchedThroughSeason: 1, knownAiredSeason: 1 });
    const after = { ...before, seasons: stored };
    expect(findDiscovery(before, after, TODAY)?.seasonNumber).toBe(2);
  });

  it('stays quiet once that season has been recorded as known', () => {
    // The refresh that announced it also raised knownAiredSeason, so the next
    // refresh must not announce the same season again.
    const stored = [season(1, '2020-01-01'), season(2, '2026-08-10')];
    const before = show({ seasons: stored, watchedThroughSeason: 1, knownAiredSeason: 2 });
    expect(findDiscovery(before, { ...before }, TODAY)).toBeNull();
  });

  it('stays quiet when the new season is already acknowledged', () => {
    const before = show({
      seasons: [season(1, '2020-01-01')],
      watchedThroughSeason: 2,
      knownAiredSeason: 1,
    });
    const after = { ...before, seasons: [season(1, '2020-01-01'), season(2, '2026-08-01')] };
    expect(findDiscovery(before, after, TODAY)).toBeNull();
  });

  it('stays quiet when the newly listed season has not aired yet', () => {
    const before = show({
      seasons: [season(1, '2020-01-01')],
      watchedThroughSeason: 1,
      knownAiredSeason: 1,
    });
    const after = { ...before, seasons: [season(1, '2020-01-01'), season(2, '2027-01-01')] };
    expect(findDiscovery(before, after, TODAY)).toBeNull();
  });

  it('announces the first ever aired season of a newly started show', () => {
    const before = show({ seasons: [season(1, '2027-01-01')], watchedThroughSeason: 0 });
    const after = { ...before, seasons: [season(1, '2026-08-01')] };
    expect(findDiscovery(before, after, TODAY)?.seasonNumber).toBe(1);
  });
});

describe('the announce-once cycle end to end', () => {
  it('announces a newly aired season exactly once across repeated refreshes', () => {
    const seasons = [season(1, '2020-01-01'), season(2, '2026-08-10')];
    // Followed back when only season 1 had aired.
    let tracked = show({
      seasons: [season(1, '2020-01-01')],
      watchedThroughSeason: 1,
      knownAiredSeason: 1,
    });

    const first = mergeShow(tracked, detail({ seasons }), new Date('2026-08-14T09:00:00Z'));
    expect(findDiscovery(tracked, first, TODAY)?.seasonNumber).toBe(2);
    expect(first.knownAiredSeason).toBe(2);

    tracked = first;
    const second = mergeShow(tracked, detail({ seasons }), new Date('2026-08-14T21:00:00Z'));
    expect(findDiscovery(tracked, second, TODAY)).toBeNull();
  });
});
