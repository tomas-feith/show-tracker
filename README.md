# Show Tracker

An Android app for following TV shows and finding out when a new season actually
lands, rather than six months later.

Built with React Native and Expo. Everything lives on the device: there is no
server, no account, and no data leaves the phone except the show lookups it
makes directly to TMDB.

## How it decides something is new

Each followed show carries a watermark: the highest season number you have
already been shown. It is set when you follow the show, so adding a nine-season
series does not announce nine seasons of news. A season counts as new only when
it is numbered above that watermark **and** has actually started airing, which
means:

- season 0 is ignored, since TMDB files specials and recaps there;
- a season with no air date, a future air date, or no episodes is ignored, since
  TMDB lists announced seasons months ahead of release.

New seasons are announced once, on the refresh where they first appear. "Mark as
seen" raises the watermark and clears the badge.

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

Background checks and notifications do not work under Expo Go; they need a
development build or a real APK.

## Building an installable APK

Local builds need the Android SDK. Expo's cloud builder does not:

```sh
npx eas build --platform android --profile preview
```

That produces a downloadable APK you can install directly on your phone.

## Development

```sh
npm test        # jest, covers the season and refresh logic
npm run typecheck
npm run lint
```

The logic worth testing is pure and lives in `src/core/`: `newness.ts` decides
what counts as aired and new, `refresh.ts` folds TMDB responses into stored shows
without trampling your watermark.

## Layout

```
src/
  api/tmdb.ts            TMDB client, bounded-concurrency batch fetch
  core/newness.ts        what has aired, what is new, how to sort
  core/refresh.ts        merging fresh data, deciding what to announce
  state/                 library state and persistence wiring
  storage/store.ts       AsyncStorage reads and writes
  notifications/         background task and local notifications
  screens/               library, search, detail, settings
```

Data from [TMDB](https://www.themoviedb.org/). This product uses the TMDB API but
is not endorsed or certified by TMDB.
