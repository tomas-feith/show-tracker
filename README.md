# Show Tracker

An Android app for following TV shows and finding out when a new season actually
lands, rather than six months later.

Native Kotlin and Jetpack Compose. Everything lives on the device: there is no
server, no account, and no data leaves the phone except the show lookups it
makes directly to TMDB.

> **Ported from React Native.** This app was Expo until 2026-08-15; see
> `CLAUDE.md` for why it moved. The React Native version is tagged `rn-final`,
> and `docs/INSTALLING.md` covers moving a library across.

## Tracking what you have watched

Open a show and tap the last season you finished. Everything aired above that is
backlog, and the library shows how deep it goes - "Season 4 out 2 months ago" for
one season, "3 seasons behind" for more. Tapping the same season again clears it,
so a mis-tap is undoable. Following a show assumes you are up to date; change it
if you are not.

The play button beside an aired season marks it as the one you are partway
through, and tapping it again clears it. A show with a season underway says
"Watching season 4 - 2 more waiting" and sorts above the rest of the library,
since a season already started is a better answer to "what do I put on" than one
never opened. What is waiting is the rest of the backlog, which can sit below the
season in progress as easily as above it - skipping ahead to the newest season
leaves the older ones waiting.

That marker is a third piece of state rather than a half-step on
`watchedThroughSeason`, because "started season 4" and "finished season 3" are
different claims - you can start a show in the middle, or skip ahead. Marking a
season watched drops the marker on it, since a finished season is no longer in
progress; moving the watermark backwards leaves it alone.

## How it decides something is new

Each show carries two separate watermarks, which sounds like one too many until
you try to merge them:

- `watchedThroughSeason` is your progress, and drives the backlog count.
- `knownAiredSeason` is what the app has already notified you about, and only
  stops the same season being announced twice.

Merged into one, dismissing a notification would silently claim you had watched
the season, and marking a season watched would suppress the alert for the next.

Following a show assumes you are up to date, but only as far as a season that has
finished. A season still releasing episodes is one nobody can be up to date with,
so the watched-through mark stops one below it - otherwise adding a show midway
through a run silently marks that whole season watched, which stays hidden behind
the green "next episode" line until the finale airs and it disappears.

While that run is still dropping episodes, and nothing older is waiting behind
it, the library shows the next episode rather than the backlog: "S01E02 in 4
days", not "Season 1 out 3 days ago". A weekly show is something to keep up with.
With older seasons unwatched as well, the depth wins the headline again.

"Still dropping episodes" is decided the same way for the label as for the
watermark, and deliberately not by whether TMDB currently names a next episode -
it clears that marker in the gap between two episodes as well as after a finale.
On those days the show reads "Season 4 airing - 3 of 8 out" instead, rather than
flapping back to a backlog until the next episode is published.

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

## Finding a show

The magnifier in the library filters by name as you type. Accents are folded, so
"shogun" finds "Shōgun" and "gloria" finds "Glória" without hunting for the right
diacritic on the keyboard, and the match is on any part of the name rather than
its start - "bear" finds "The Bear". Closing the box clears it, so the library is
never left quietly filtered.

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

## Backing up and restoring

A library lives only inside its own app's storage. An export is a versioned JSON
file holding every show followed, both watermarks and any in-progress marker per
show, and the last check timestamp - and it is the only way a library moves between installs, including
across the port from the React Native build tagged `rn-final`.

```json
{ "format": "showtracker-export", "version": 1,
  "exportedAt": "...", "lastCheckedAt": "...", "shows": [ ... ] }
```

The TMDB key is deliberately **not** in the file. It is a credential and the
export is designed to leave the device; pasting the key again afterwards takes
seconds.

`format` and `version` are stamped so the importer refuses a file it does not
understand rather than half-loading it. `version` gates breaking changes only: a
later writer's added field, such as `inProgressSeason`, is ignored by an older
reader rather than refused, so files still move in both directions.

This app both reads and writes that format, from Settings, then **Your data**.
Import replaces the whole library and asks first. Export is how a backup leaves
the phone now that the React Native build is gone - the library lives only in
this app's own storage, so an uninstall takes it with it.

## Layout

```
app/src/main/java/com/showtracker/app/
  domain/      what has aired, how far behind you are, what to announce
  data/        Room entities and DAO, settings, the export format
  network/     TMDB client, bounded-concurrency batch fetch
  notify/      the periodic worker and its notification
  ui/          library, search, detail, settings
```

The logic worth testing is pure and lives in `domain/`. `Newness.kt` decides what
counts as aired and how far behind you are; `Refresh.kt` folds TMDB responses
into stored shows without trampling your progress. Both came across from the
React Native build with their test suites intact.

Data from [TMDB](https://www.themoviedb.org/). This product uses the TMDB API but
is not endorsed or certified by TMDB.
