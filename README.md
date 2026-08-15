# Show Tracker

An Android app for following TV shows and finding out when a new season actually
lands, rather than six months later.

Native Kotlin and Jetpack Compose. Everything lives on the device: there is no
server, no account, and no data leaves the phone except the show lookups it
makes directly to TMDB.

> **Port in progress.** This app was originally React Native + Expo. It is being
> rebuilt in Kotlin to match the sibling `habit_tracker`; see `CLAUDE.md` for the
> reasoning and the phase plan. The React Native version is tagged `rn-final`,
> and the sections below describing behaviour still document the intended
> product - not all of it has been rebuilt yet.

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

Requires JDK 17, the Android SDK (`ANDROID_HOME` set), and a free TMDB API key.

```sh
./gradlew assembleDebug
```

The debug build installs as `com.showtracker.app.debug`, deliberately a separate
application id, so it sits alongside the React Native build still on the phone
rather than demanding to replace it.

Get a TMDB key at <https://www.themoviedb.org/settings/api> - sign up, then
Settings, then API, then request a Developer key. Either a v3 API key or a v4
read access token works. The key is kept in the device's own storage and is
never written into this repository.

## Development

```sh
./gradlew staticAnalysis      # ktlint + detekt
./gradlew testDebugUnitTest
./gradlew lintDebug           # warnings are errors
```

Enable the pre-commit hook once per clone; it runs ktlint and detekt:

```sh
git config core.hooksPath .githooks
```

CI runs all of the above on every push and pull request to `main`.

## Moving a library across the port

The React Native build, tagged `rn-final`, has Settings, then **Export library**,
which writes a versioned JSON file and hands it to the share sheet: every show
followed, both watermarks per show, and the last check timestamp. A library
lives only inside its own app's storage, so that file is the only way across -
this app cannot read the old one's data, and an uninstall takes it with it.

```json
{ "format": "showtracker-export", "version": 1,
  "exportedAt": "...", "lastCheckedAt": "...", "shows": [ ... ] }
```

The TMDB key is deliberately **not** in the file. It is a credential and the
export is designed to leave the device; pasting the key again afterwards takes
seconds.

`format` and `version` are stamped so the importer can refuse a file it does not
understand rather than half-loading it. The importer is not built yet; it lands
with the data layer.

## Layout

```
app/src/main/java/com/showtracker/app/
  ui/theme/       the single dark palette, ported from the old theme.ts
  MainActivity.kt placeholder until the library screen lands
```

Data from [TMDB](https://www.themoviedb.org/). This product uses the TMDB API but
is not endorsed or certified by TMDB.
