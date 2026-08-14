# Show Tracker

An Android app for following TV shows and finding out when a new season actually
lands, rather than six months later.

Built with React Native and Expo. Everything lives on the device: there is no
server, no account, and no data leaves the phone except the show lookups it
makes directly to TMDB.

## Tracking what you have watched

Open a show and tap the last season you finished. Everything aired above that is
backlog, and the library shows how deep it goes - "Season 4 out 2 months ago" for
one season, "3 seasons behind" for more. Tapping the same season again clears it,
so a mis-tap is undoable. Following a show assumes you are up to date; change it
if you are not.

## How it decides something is new

Each show carries two separate watermarks, which sounds like one too many until
you try to merge them:

- `watchedThroughSeason` is your progress, and drives the backlog count.
- `knownAiredSeason` is what the app has already notified you about, and only
  stops the same season being announced twice.

Merged into one, dismissing a notification would silently claim you had watched
the season, and marking a season watched would suppress the alert for the next.

A season counts as aired - and so as backlog - only when it has a real season
number, a past air date, and at least one episode. All three matter:

- season 0 is ignored, since TMDB files specials and recaps there;
- TMDB lists announced seasons months ahead of release, sometimes with a
  placeholder date and no episodes.

That last point is also why `knownAiredSeason` is recorded rather than
recomputed. The stored season list is always evaluated against today's date, so
a season TMDB had listed early would look like it had aired all along once its
date passed, and the moment it actually dropped would go unannounced - which is
the entire failure this app exists to prevent. There is a regression test for it.

## Checking for updates

- **On open** - if the last check is more than six hours old, the library
  refreshes when you open the app or return to it from the background.
- **In the background** - a device-side task asks Android to run roughly twice a
  day and posts a local notification if anything dropped. Android decides when
  this actually runs and may skip it on a low battery, so it is a bonus on top
  of the on-open check, not a guarantee.

## Setup

Requires Node 20+ and a free TMDB API key.

```sh
npm install
npm start
```

Scan the QR code with Expo Go on your phone, then open Settings in the app and
paste your TMDB key. Get one at <https://www.themoviedb.org/settings/api> - sign
up, then Settings, then API, then request a Developer key. Either a v3 API key or
a v4 read access token works.

The key is validated before it is stored, and it is kept in the device's own
storage. It is never written into this repository.

Expo Go removed the notification native module in SDK 53, so under Expo Go the
app runs and refreshes normally but cannot post notifications or run background
checks, and Settings says so. Both modules are loaded lazily for this reason - a
plain top-level import of `expo-notifications` crashes Expo Go on launch, before
the first render. For the real behaviour, build the APK below.

## Building an installable APK

Local builds need the Android SDK. Expo's cloud builder does not:

```sh
npx eas build --platform android --profile preview
```

That produces a downloadable APK you can install directly on your phone.

## Development

```sh
npm test        # jest, covers the season, refresh and migration logic
npm run typecheck
npm run lint
```

Enable the pre-commit hook once per clone; it runs the same three checks:

```sh
git config core.hooksPath .githooks
```

CI runs them on every push and pull request to `main`, plus `expo-doctor` to
catch dependency drift against the Expo SDK.

The logic worth testing is pure and lives in `src/core/`: `newness.ts` decides
what counts as aired and how far behind you are, `refresh.ts` folds TMDB
responses into stored shows without trampling your progress. `migrateShows` in
`src/storage/store.ts` is exported and tested for the same reason - the upgrade
path runs against data an older install wrote, which normal use cannot
reproduce.

## Layout

```
src/
  api/tmdb.ts            TMDB client, bounded-concurrency batch fetch
  core/newness.ts        what has aired, how far behind you are, how to sort
  core/refresh.ts        merging fresh data, deciding what to announce
  state/                 library state and persistence wiring
  storage/store.ts       AsyncStorage reads, writes and schema migration
  notifications/         background task and local notifications
  screens/               library, search, detail, settings
```

Data from [TMDB](https://www.themoviedb.org/). This product uses the TMDB API but
is not endorsed or certified by TMDB.
