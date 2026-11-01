# Contributing to Files

Thanks for helping improve Files. This guide explains how to propose a change.

## Before you start

- For bugs, open an issue with your Android version, phone model, the app
  version (Settings) and the steps to reproduce. Settings > Logs can copy a
  log; check it for private file names before you paste it into a public issue.
- For new features or larger changes, open an issue first so the approach can
  be agreed before you spend time on it.
- Security problems are never reported in public issues; see
  [`SECURITY.md`](SECURITY.md).

## Building and testing

See "Build from source" in [`README.md`](README.md#build-from-source). Before opening a pull
request, run:

```
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

All unit tests must pass and lint must report no errors. Add or update unit
tests for the behaviour you change (tests live in `app/src/test/`, with
Robolectric and Roborazzi for UI). The pull-request workflow
(`.github/workflows/ci.yml`) runs the same checks without any signing keys.

## Project rules

- **Fully offline.** The app has no `INTERNET` permission and the build fails
  if one appears in the APK. Do not add network code, analytics, ads, crash
  reporters that upload data, or libraries that need network access.
- **No secrets in the repository.** Never commit keystores, passwords, API keys
  or `local.properties`.
- **Permissions.** Do not add a new Android permission without explaining in
  the pull request why the feature needs it.
- **Code style.** Kotlin with the official code style
  (`kotlin.code.style=official`), Jetpack Compose and Material 3. Keep changes
  focused; do not reformat unrelated code.

## Dependencies and licences

The proposed licence for Files is Apache-2.0 (decision pending; see the
"Licence (pending)" section of the README), so every new dependency must have a
licence that allows that:

- Accepted: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, Zlib,
  public domain / Unlicense.
- Needs discussion first (open an issue): LGPL, MPL, EPL and any other
  weak-copyleft licence, or any library that bundles native code, fonts or
  data files.
- Not accepted: GPL, AGPL, licences that restrict commercial use or field of
  use, and code with no licence.

The two existing exceptions, FFmpeg (LGPL-2.1-or-later, built in
`decoder-ffmpeg/`) and Junrar (UnRAR licence), are explained in
`THIRD_PARTY_LICENSES.md`; they do not make other such libraries acceptable.
Changes to `decoder-ffmpeg/` or `scripts/ffmpeg/` must keep FFmpeg free of GPL,
version3 and non-free parts, keep the upstream AndroidX Media files unmodified
(or record every change in `decoder-ffmpeg/UPSTREAM.md`), and update the pins,
notices and `RELINKING.md` together. Contributions that touch Junrar must keep
its UnRAR licence notice and its restriction.

When you add, remove or upgrade a library, update
[`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md) (name, version, licence,
copyright holder, source link) and, if the library has a NOTICE file, the
[`NOTICE`](NOTICE) file. Do not copy code, icons, images, fonts, sounds or
test files from other projects unless their licence allows it; say where they
came from in the pull request.

## Licensing of contributions

**Pending:** the project licence has not been confirmed yet (see the
"Licence (pending)" section of the README). Once it is, contributions will be
licensed under it; the proposed licence is the Apache License, Version 2.0,
whose section 5 covers contributions (text kept for review in
[`LICENSE.pending-review.md`](LICENSE.pending-review.md)). By submitting a pull
request you confirm that you have the right to submit the work. Contributors
keep the copyright in their own contributions.

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make your change with tests, and keep commits focused with clear messages.
3. Run the checks above.
4. Open a pull request that explains what changed and why, and how you tested
   it (include screenshots for visible changes).

A maintainer will review the pull request and may ask for changes. Merged
changes reach users in the next build made by CLP Studio.
