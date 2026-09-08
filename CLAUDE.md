# Stack

Native Kotlin + Jetpack Compose, targeting Android only. Mirrors the sibling
`habit_tracker`, which is the reference for how this project is set up: same
Gradle version catalog, same ktlint + detekt + `staticAnalysis` task, same
`warningsAsErrors` lint policy, same absent-tolerant `keystore.properties`
signing block.

Unlike habit_tracker, this app **does** need the network - season data comes from
TMDB - so it declares `INTERNET`. Nothing else leaves the device: no account, no
server, no analytics.

## Port in progress

This was a React Native + Expo app until 2026-08-15. Expo was the wrong fit: the
cross-platform payoff never landed (Android only), and Expo Go's sandbox cannot
post notifications or run background tasks, which forced lazy `require`s and
null-tolerant callers throughout the notification layer. Kotlin is now the
default for Android work here; see the global `~/.claude/CLAUDE.md`.

The React Native version is tagged **`rn-final`**. Recover it with
`git checkout rn-final` if a reference is needed; it is no longer installed
anywhere.

**The cutover is done.** It happened on 2026-08-15: `com.showtracker.app` on the
phone is the Kotlin release, holding the live library in its Room database, and
the React Native build is gone. This file and `docs/INSTALLING.md` both said
otherwise until 2026-08-19, and that stale claim caused a real scare - an
`adb install -r` was run expecting Android to refuse it on a certificate
mismatch, and it succeeded, because there was no mismatch and never had been.
Verify what is on the phone before trusting a document about it.

Phases 0-6 are all built: export (in `rn-final`), skeleton, domain + Room and
the importer, TMDB client, Compose UI, WorkManager + notifications, and the
import/export UI.

Built since, beyond the original phases:

- **Discover** (`ui/discover`). Two tabs. "For you" is a local recommender: one
  `/tv/{id}/recommendations` call per followed show, pooled and ranked by how
  many of the user's shows produced the same suggestion, tie-broken by a
  vote-count-damped rating (`domain/Recommend.kt`). TMDB's own account-level
  recommender is deliberately not used - it wants a TMDB login and the user's
  ratings on TMDB's servers, and this app has no account. "Trending" is the
  plain `/trending/tv/week` list, kept separate so a suggestion claiming to be
  about your library always is. Refresh pages down the ranked pool rather than
  re-rolling seeds, which would destroy the agreement signal the ranking rests
  on. "Not interested" hides a show for good; Settings lists what is hidden and
  offers it back.
- **Backups.** Two independent mechanisms, and they answer different questions.
  Android Auto Backup covers a lost phone; `LibraryBackupAgent` checkpoints the
  WAL first, without which the cloud copy is the database as of the last
  checkpoint and loses everything since. The scheduled export
  (`notify/BackupWorker`) covers going back to how the library looked before a
  mistake: a dated JSON written daily into a folder the user picks through SAF,
  keeping the last 14.
- **Schema is at version 6.** 2 added `inProgressSeason`, 3 added
  `shows.overview` and the `dismissed` table, 4 added `dismissed.name`, 5 added
  the show metadata TMDB already sends on `/tv/{id}` and the client used to
  discard: `voteAverage`/`voteCount`, `genres`, `episodeRunTime`, `type` and
  `numberOfEpisodes`. Genres are one column joined by **U+001F**, the ASCII unit
  separator, not a table: nothing queries by genre, and the seasons table exists
  because a season is a nested object, which a genre name is not. The separator
  is a control character rather than a pipe or a comma because a printable one
  is a bet on TMDB never adding a genre name containing it, and losing that bet
  would silently split one tag into two that do not exist. `episodeRunTime` is the only nullable one -
  TMDB's `episode_run_time` is a legacy field it often leaves empty, so the
  client averages it when present, falls back to the runtime on the last aired
  episode, and stores null when there is neither. A 0 would render as "0m".
  6 added `shows.favourite`, the user's star. It is the third column TMDB is not
  the source of - with `watchedThroughSeason` and `inProgressSeason` - so the
  export carries it (nothing could refetch it), and `BACKFILL_VERSION` was
  deliberately *not* bumped for it.
- **A refresh writes only the columns TMDB owns** (`RefreshedShow`, a Room
  partial update). It reads the library, spends seconds in TMDB, then writes
  back - so a whole-row upsert silently reverted anything the user did in
  between: a star tapped mid-refresh came back off, a season marked watched came
  back unwatched, and a show removed mid-refresh was resurrected. The statement
  now names TMDB's columns plus `knownAiredSeason` and `lastCheckedAt`, which
  belong to the refresh itself, and updates 0 rows for a show that has gone.
- **Favourites.** A star on the show screen, a marker on the library row, a
  "Starred only" chip in the filter sheet, and the seed set behind the
  "Favourites" discovery tab. A flag on `shows` rather than its own table,
  because a favourite is by definition a followed show and two tables could
  disagree about that; contrast `dismissed`, which is its own table precisely
  because a dismissed show is one that is *not* followed.
- **"More like this"** at the foot of the show screen (`ui/detail`). One
  `/tv/{id}/recommendations` call for the show being looked at, in TMDB's own
  order - the discovery ranking is agreement between several seeds, and with one
  seed there is nothing to agree. Followed shows stay in the list wearing the
  tick, unlike the discovery tabs, because this section answers "what is this
  show like" rather than "what next". `ShowDetailViewModel` is scoped to the
  navigation entry, so it belongs to the show on screen; it shares
  `PreviewController`, so following and "not interested" behave exactly as they
  do in discovery.
- **The "Favourites" discovery tab** is "For you" seeded by the starred shows
  alone. It has to be a separate tab rather than a weighting: the ranking is
  agreement between seeds, so in a library of eighty shows whatever the bulk has
  in common wins every tie and five favourites change nothing. Each seeded tab
  keeps its own ranked pool and page; a dismissal sweeps both, since it is about
  the show and not the tab it was seen on.

Five things that are not obvious from the code:

- A ViewModel here can be unit-tested because `ApiKeySource` and
  `DiscoverLibrary` (`data/Sources.kt`) exist, and because `TmdbClient` takes
  its IO dispatcher. Both seams were added after two logic bugs shipped in
  `DiscoverViewModel` that a JVM test would have caught, and could not be
  written because the ViewModel needed a `Context` to construct.
- A schema change and the test assets are both generated into `app/schemas`, so
  the first build after an entity change packages the *previous* schema and
  `MigrationTest` fails against it. Build twice; the second run is the real
  result.
- **`assembleDebug` and `testDebugUnitTest` do not compile `src/androidTest`.**
  A local run of both can be green while `compileDebugAndroidTestKotlin` is
  broken, and CI - which does build it - then fails on a push that looked
  clean. `ShowDaoTest` constructs `ShowEntity` exhaustively, so every added
  column breaks it. Run `:app:assembleDebugAndroidTest` before pushing; it needs
  no device, unlike `connectedDebugAndroidTest`.
- `DiscoverTab` is declared in the order the tabs are drawn, because `TabRow`
  reads the ordinal. Reordering the enum moves the tabs on screen.
- **`LibraryViewModel.BACKFILL_VERSION` needs bumping whenever a migration adds
  a column TMDB is the source of.** It is what makes the app refresh once on the
  next open instead of waiting up to six hours for the library to go stale, so
  forgetting it leaves the new column blank on the screen the user just updated
  to see it. It is deliberately not `ShowDatabase.VERSION`: a migration that
  adds something the user owns rather than something TMDB sends - `dismissed.name`
  was one - needs no refetch. The value is recorded in `Settings` after a refresh
  that lost nothing, rather than inferred from the rows: "every show has no
  genres" cannot tell a library that has not refreshed yet from one TMDB has no
  genres for, and the second re-refreshed on every app open, for ever.

The logic worth porting carefully lives in `rn-final` under `src/core/`:
`newness.ts` decides what counts as aired, `refresh.ts` folds TMDB responses into
stored shows without trampling the user's progress. Both have Jest suites that
should come across as JUnit tests, **including the `knownAiredSeason` regression
test** - that one guards the exact failure the app exists to prevent.

Two traps when porting that logic:

- `daysBetween` reads both dates as UTC midnight to avoid DST skew, while
  `todayISO` is local. Use `LocalDate` + `ChronoUnit.DAYS`, not `Instant`.
- `describeDays` uses `Math.round(n / 30)`; Kotlin integer division truncates.
  Port the arithmetic explicitly or the relative-date strings drift.

## Debug builds are a separate app

`applicationIdSuffix = ".debug"`, so a debug build installs as
`com.showtracker.app.debug` with its own database and can be tried without
touching the release install. That release install holds the only live copy of
the library: it is not backed by a server, and an uninstall erases it.

`adb install -r` keeps the database, but only while the signing certificate
matches. The release keystore lives outside the repository and cannot be
regenerated; see `docs/INSTALLING.md`. Always print an APK's signer and compare
it before installing over anything holding real data - and never run an install
against that package expecting it to fail. If the point is to prove a
certificate mismatch, compare the printed digests instead.
