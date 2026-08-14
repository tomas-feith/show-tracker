import {
  daysBetween,
  describeDays,
  formatEpisode,
  hasHappened,
  hasNewSeason,
  initialWatermark,
  latestAiredSeason,
  nextUnairedSeason,
  realSeasons,
  showState,
  sortLibrary,
  todayISO,
} from '../newness';
import type { Season, TrackedShow } from '../types';

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

describe('date helpers', () => {
  it('counts whole days in both directions', () => {
    expect(daysBetween('2026-08-14', '2026-08-14')).toBe(0);
    expect(daysBetween('2026-08-10', '2026-08-14')).toBe(4);
    expect(daysBetween('2026-08-14', '2026-08-10')).toBe(-4);
  });

  it('is not skewed across a daylight saving boundary', () => {
    // Europe/Lisbon springs forward on 2026-03-29.
    expect(daysBetween('2026-03-28', '2026-03-30')).toBe(2);
  });

  it('treats today as having happened and null as not', () => {
    expect(hasHappened(TODAY, TODAY)).toBe(true);
    expect(hasHappened('2026-08-13', TODAY)).toBe(true);
    expect(hasHappened('2026-08-15', TODAY)).toBe(false);
    expect(hasHappened(null, TODAY)).toBe(false);
  });

  it('formats today as a zero-padded local date', () => {
    expect(todayISO(new Date(2026, 7, 4))).toBe('2026-08-04');
    expect(todayISO(new Date(2026, 11, 31))).toBe('2026-12-31');
  });
});

describe('latestAiredSeason', () => {
  it('ignores specials in season 0', () => {
    const seasons = [season(0, '2019-01-01'), season(1, '2019-06-01')];
    expect(realSeasons(seasons)).toHaveLength(1);
    expect(latestAiredSeason(seasons, TODAY)?.seasonNumber).toBe(1);
  });

  it('ignores a future season that TMDB has already listed', () => {
    const seasons = [season(1, '2024-01-01'), season(2, '2027-01-01')];
    expect(latestAiredSeason(seasons, TODAY)?.seasonNumber).toBe(1);
  });

  it('ignores an empty placeholder season even with a past air date', () => {
    const seasons = [season(1, '2024-01-01'), season(2, '2026-01-01', 0)];
    expect(latestAiredSeason(seasons, TODAY)?.seasonNumber).toBe(1);
  });

  it('ignores an announced season with no date at all', () => {
    const seasons = [season(1, '2024-01-01'), season(2, null)];
    expect(latestAiredSeason(seasons, TODAY)?.seasonNumber).toBe(1);
  });

  it('returns null when nothing has aired', () => {
    expect(latestAiredSeason([season(1, '2030-01-01')], TODAY)).toBeNull();
    expect(latestAiredSeason([], TODAY)).toBeNull();
  });

  it('picks the highest number rather than the last in the array', () => {
    const seasons = [season(3, '2025-01-01'), season(1, '2020-01-01'), season(2, '2022-01-01')];
    expect(latestAiredSeason(seasons, TODAY)?.seasonNumber).toBe(3);
  });
});

describe('nextUnairedSeason', () => {
  it('picks the nearest announced but unaired season', () => {
    const seasons = [season(1, '2020-01-01'), season(2, '2027-01-01'), season(3, '2028-01-01')];
    expect(nextUnairedSeason(seasons, TODAY)?.seasonNumber).toBe(2);
  });

  it('is null when every season has aired', () => {
    expect(nextUnairedSeason([season(1, '2020-01-01')], TODAY)).toBeNull();
  });
});

describe('watermark behaviour', () => {
  it('starts level with the current season so adding a show is quiet', () => {
    const seasons = [season(1, '2020-01-01'), season(2, '2021-01-01'), season(3, '2022-01-01')];
    const watermark = initialWatermark(seasons, TODAY);
    expect(watermark).toBe(3);
    expect(hasNewSeason(show({ seasons, acknowledgedSeason: watermark }), TODAY)).toBe(false);
  });

  it('reports a new season once one airs above the watermark', () => {
    const seasons = [season(1, '2020-01-01'), season(2, '2026-02-01')];
    expect(hasNewSeason(show({ seasons, acknowledgedSeason: 1 }), TODAY)).toBe(true);
  });

  it('does not report an unreleased season as new', () => {
    const seasons = [season(1, '2020-01-01'), season(2, '2027-02-01')];
    expect(hasNewSeason(show({ seasons, acknowledgedSeason: 1 }), TODAY)).toBe(false);
  });

  it('handles a show with no aired seasons at all', () => {
    expect(initialWatermark([], TODAY)).toBe(0);
    expect(hasNewSeason(show({ seasons: [] }), TODAY)).toBe(false);
  });
});

describe('showState', () => {
  it('prioritises an unseen new season over an upcoming episode', () => {
    const state = showState(
      show({
        seasons: [season(1, '2020-01-01'), season(2, '2026-06-01')],
        acknowledgedSeason: 1,
        nextEpisode: { seasonNumber: 2, episodeNumber: 8, name: 'Later', airDate: '2026-09-01' },
      }),
      TODAY
    );
    expect(state.kind).toBe('new_season');
    if (state.kind === 'new_season') {
      expect(state.season.seasonNumber).toBe(2);
      expect(state.daysAgo).toBe(74);
    }
  });

  it('reports an airing show by its next episode', () => {
    const state = showState(
      show({
        seasons: [season(1, '2026-08-01')],
        acknowledgedSeason: 1,
        nextEpisode: { seasonNumber: 1, episodeNumber: 3, name: 'Next', airDate: '2026-08-21' },
      }),
      TODAY
    );
    expect(state).toEqual({
      kind: 'airing',
      next: { seasonNumber: 1, episodeNumber: 3, name: 'Next', airDate: '2026-08-21' },
      daysUntil: 7,
    });
  });

  it('falls back to an announced season when no episode is scheduled', () => {
    const state = showState(
      show({
        seasons: [season(1, '2020-01-01'), season(2, '2026-11-01')],
        acknowledgedSeason: 1,
      }),
      TODAY
    );
    expect(state.kind).toBe('upcoming');
  });

  it('marks finished shows as ended', () => {
    const state = showState(
      show({ seasons: [season(1, '2020-01-01')], acknowledgedSeason: 1, status: 'Ended' }),
      TODAY
    );
    expect(state.kind).toBe('ended');
  });

  it('marks a returning show with nothing scheduled as waiting', () => {
    const state = showState(
      show({ seasons: [season(1, '2020-01-01')], acknowledgedSeason: 1 }),
      TODAY
    );
    expect(state.kind).toBe('waiting');
  });

  it('does not treat a past next-episode marker as upcoming', () => {
    // TMDB occasionally leaves a stale next_episode_to_air behind.
    const state = showState(
      show({
        seasons: [season(1, '2020-01-01')],
        acknowledgedSeason: 1,
        status: 'Ended',
        nextEpisode: { seasonNumber: 1, episodeNumber: 9, name: 'Stale', airDate: '2020-05-01' },
      }),
      TODAY
    );
    expect(state.kind).toBe('ended');
  });
});

describe('sortLibrary', () => {
  it('floats new seasons to the top and ended shows to the bottom', () => {
    const shows = [
      show({ id: 1, name: 'Ended Show', seasons: [season(1, '2019-01-01')], acknowledgedSeason: 1, status: 'Ended' }),
      show({
        id: 2,
        name: 'Airing Show',
        seasons: [season(1, '2026-08-01')],
        acknowledgedSeason: 1,
        nextEpisode: { seasonNumber: 1, episodeNumber: 3, name: 'x', airDate: '2026-08-20' },
      }),
      show({
        id: 3,
        name: 'New Season Show',
        seasons: [season(1, '2020-01-01'), season(2, '2026-03-01')],
        acknowledgedSeason: 1,
      }),
    ];
    expect(sortLibrary(shows, TODAY).map((s) => s.id)).toEqual([3, 2, 1]);
  });

  it('orders multiple new seasons by most recent drop first', () => {
    const shows = [
      show({ id: 1, name: 'Older', seasons: [season(1, '2019-01-01'), season(2, '2026-01-01')], acknowledgedSeason: 1 }),
      show({ id: 2, name: 'Newer', seasons: [season(1, '2019-01-01'), season(2, '2026-08-01')], acknowledgedSeason: 1 }),
    ];
    expect(sortLibrary(shows, TODAY).map((s) => s.id)).toEqual([2, 1]);
  });

  it('does not mutate the input array', () => {
    const shows = [show({ id: 1, name: 'B' }), show({ id: 2, name: 'A' })];
    const original = [...shows];
    sortLibrary(shows, TODAY);
    expect(shows).toEqual(original);
  });
});

describe('formatting', () => {
  it('describes day counts in human terms', () => {
    expect(describeDays(0, 'ago')).toBe('today');
    expect(describeDays(1, 'ago')).toBe('yesterday');
    expect(describeDays(5, 'ago')).toBe('5 days ago');
    expect(describeDays(180, 'ago')).toBe('6 months ago');
    expect(describeDays(400, 'ago')).toBe('over a year ago');
    expect(describeDays(1, 'until')).toBe('tomorrow');
    expect(describeDays(10, 'until')).toBe('in 10 days');
  });

  it('zero-pads episode codes', () => {
    expect(formatEpisode({ seasonNumber: 2, episodeNumber: 5, name: '', airDate: null })).toBe('S02E05');
    expect(formatEpisode({ seasonNumber: 12, episodeNumber: 134, name: '', airDate: null })).toBe('S12E134');
  });
});
