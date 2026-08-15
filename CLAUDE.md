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

The React Native version is tagged **`rn-final`** and is still the build running
on the phone. Recover it with `git checkout rn-final` if a reference is needed.

Phases 0-6 are all built: export (in `rn-final`), skeleton, domain + Room and
the importer, TMDB client, Compose UI, WorkManager + notifications, and the
import/export UI. What remains is the cutover itself, which is manual and
described in `docs/INSTALLING.md`.

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

`applicationIdSuffix = ".debug"`. The release `com.showtracker.app` on the phone
is the React Native build and holds the only live copy of the library, so a debug
build must sit beside it rather than demand to replace it - under a different
signing key, replacing means uninstalling first, which erases that library.

At cutover the old app is uninstalled deliberately and the library restored from
the export file. Do not uninstall it before then.

The release keystore lives outside the repository and cannot be regenerated; see
`docs/INSTALLING.md`. Always print an APK's signer and compare it before
installing over anything holding real data.
