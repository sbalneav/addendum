# CLAUDE.md

Addendum: an Android app that fills in a simple form and has a script on your
own server append the result to a text file, over SSH. Each kind of file is a
"form": a small script on the server that describes its own fields.
**Read ARCHITECTURE.md first.** It has the design, the wire protocol and the
spec format. Deployment-specific notes, if any, are in CLAUDE.local.md (not in git).

## Layout

- `server/addendum`: the dispatcher, the SSH forced command (`~/bin/addendum`).
- `server/lib.sh`: helpers for form scripts (`~/.local/share/addendum/lib.sh`).
- `server/forms/*`: forms installed by `make deploy` (`~/.config/addendum/forms/`).
  - `appointment`: appends `Nov 24<TAB>Dentist @ 08:00` to `$CALENDAR_DIR/calendar.appointments`.
  - `ledger`: records one expense in the ledger-cli journal `$LEDGER_FILE`.
- `server/examples/demo`: every field type, writes to `~/.local/state/addendum/demo.txt`.
  Not deployed by make; install it by hand when testing.
- `app/`: the Android app, package `ca.balneaves.addendum`.

## Server

- `make test` runs `server/test.sh`: the dispatcher and all forms against throwaway
  copies (a fake calendar dir, a scratch journal). It never touches real files.
- `make deploy SERVER=host` runs the tests, then streams each piece to the server
  with `install`. Put `SERVER=host` in `local.mk` (git-ignored) to make it the default.
- Never test `add` against a real calendar or journal. Read-only `spec` and
  `status` against the real ones are fine.
- The protocol is defined in `server/addendum` and `app/.../FormServer.kt`. Change
  both and update ARCHITECTURE.md.
- Forms get env vars like `$CALENDAR_DIR` and `$LEDGER_FILE` through `login_env`,
  because SSH forced commands don't read `~/.profile`.

## Build (command line only; Android Studio isn't needed)

JDK 17 or later, Android SDK, `local.properties` (git-ignored) with `sdk.dir=...`.

- `./gradlew testDebugUnitTest`: unit tests for the spec parser and form logic (`SpecTest.kt`).
- `./gradlew assembleDebug`: `app/build/outputs/apk/debug/app-debug.apk`, id
  `ca.balneaves.addendum.debug`, installs alongside the release build, and `run-as` works.
- `./gradlew assembleRelease`: R8-minified, signed if `~/.android-keys/addendum.properties`
  exists (storeFile, storePassword, keyAlias, keyPassword), otherwise unsigned.
- Each build (debug, release) has its own SSH key, profiles and outbox.
- Read a debug build's public key with
  `adb shell run-as ca.balneaves.addendum.debug cat files/id_ecdsa.pub`.

## Conventions

- Keep it small: one record per send, no editing or browsing files. That's what the shell is for.
- Forms only ever append; they never change existing lines.
- Money and numbers stay strings, never Double.
- Branch `master`.
