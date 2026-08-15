# Installing, updating, and moving from the React Native build

This app is side-loaded. There is no Play listing, so installing and updating are
both `adb install`, and the signing key is yours to keep.

## The signing key

Release builds are signed with a keystore held outside this repository:

```
OneDrive/Documentos/Android_Apps_Passwords/Show_Tracker/showtracker-release.jks
```

`keystore.properties` in the repository root points at it and is gitignored. On
CI and on a fresh clone that file is absent, and the release build simply comes
out unsigned - which is correct. An unsigned artifact is obviously unusable,
whereas one silently signed with the debug key looks fine and then cannot be
updated by a real release later.

**This key cannot be regenerated.** Android identifies an installed app by
applicationId plus signing certificate. Lose it and `com.showtracker.app` can
never be updated in place again; the only way forward is a different
applicationId and a fresh install, which destroys the database - the
watched-through progress for every show. Nothing else holds that progress.

Current certificate:

```
SHA-256  A9:D4:7B:33:8F:90:B3:9A:EF:37:07:C1:46:DC:38:5B:
         CD:EF:71:41:15:7F:91:C1:82:B2:65:E0:B2:52:B6:C8
```

## Building

```sh
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` updates in place and keeps the database. That only works while the
certificate matches; verify before installing anything you care about:

```sh
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Play Protect will ask you to confirm a side-loaded install. **Unlock the phone
first** - the prompt cannot be answered on a locked screen, and `adb install`
will simply sit there waiting until it times out.

## Debug builds sit alongside

The debug build has `applicationIdSuffix = ".debug"`, so it installs as
`com.showtracker.app.debug` and is a separate app with a separate database. That
is deliberate: you can try a change without touching the release install holding
your real library.

It also means an import into the debug build does **not** populate the release
build. Rehearsing there is free.

## Moving a library from the React Native build

The React Native version is tagged `rn-final` and used the same applicationId,
`com.showtracker.app`, but was signed with the stock Android debug key - Expo's
template wires `release` to `signingConfigs.debug`. Its certificate is therefore
different from the one above, so the Kotlin release **cannot** be installed over
it. Android refuses an update whose signing certificate changed.

That makes the move a deliberate uninstall, and the order matters:

1. In the React Native app: Settings, then **Export library**. Save the JSON
   somewhere off the phone - Drive, or email it to yourself. It must survive the
   uninstall in step 3.
2. Install the Kotlin **debug** build, import the file, and check the show count
   and a few watched-through positions. Nothing is irreversible yet: the old app
   is still installed and untouched.
3. Uninstall the React Native app. This erases its library. Do not do this until
   step 2 has convinced you the file is good.
4. Install the Kotlin release build and import the same file again. Step 2 used a
   different app with its own database, so the data does not carry over.
5. Paste the TMDB key. It is deliberately not in the export - it is a credential,
   and the file is designed to leave the phone.

## Backups

Android backs up the database to the user's Google account, so a new phone keeps
the shows and the watched-through progress. The settings store is excluded, so
the TMDB key does not travel. See `app/src/main/res/xml/backup_rules.xml`.

A backup is not a substitute for an export: it is restored only by Android, only
onto a matching install, and never on demand.
