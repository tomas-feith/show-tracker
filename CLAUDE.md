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
