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
- **Schema is at version 5.** 2 added `inProgressSeason`, 3 added
  `shows.overview` and the `dismissed` table, 4 added `dismissed.name`, 5 added
  the show metadata TMDB already sends on `/tv/{id}` and the client used to
  discard: `voteAverage`/`voteCount`, `genres`, `episodeRunTime`, `type` and
  `numberOfEpisodes`. Genres are one pipe-joined column, not a table: nothing
  queries by genre, and the seasons table exists because a season is a nested
  object, which a genre name is not. `episodeRunTime` is the only nullable one -
  TMDB's `episode_run_time` is a legacy field it often leaves empty, so the
  client averages it when present, falls back to the runtime on the last aired
  episode, and stores null when there is neither. A 0 would render as "0m".

Two things that are not obvious from the code:

- A ViewModel here can be unit-tested because `ApiKeySource` and
  `DiscoverLibrary` (`data/Sources.kt`) exist, and because `TmdbClient` takes
  its IO dispatcher. Both seams were added after two logic bugs shipped in
  `DiscoverViewModel` that a JVM test would have caught, and could not be
  written because the ViewModel needed a `Context` to construct.
- A schema change and the test assets are both generated into `app/schemas`, so
  the first build after an entity change packages the *previous* schema and
  `MigrationTest` fails against it. Build twice; the second run is the real
  result.

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
