# F-Droid build recipe

This file is the source-of-truth for the build inputs F-Droid's
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) metadata entry will
mirror. Keep it in sync with `app/build.gradle.kts` whenever those values
change — the F-Droid metadata is downstream of this file.

## Build inputs

| Field | Value | Source |
|---|---|---|
| Build system | Gradle (pinned wrapper) | `gradle/wrapper/gradle-wrapper.properties` → 8.6 |
| JDK | Temurin 17 | `compileOptions.sourceCompatibility = VERSION_17` |
| Android compileSdk | 34 | `app/build.gradle.kts` |
| Android targetSdk | 34 | `app/build.gradle.kts` |
| Android minSdk | 23 | `app/build.gradle.kts` |
| Kotlin | 1.9.x (whatever the root buildscript pins) | `build.gradle.kts` root |
| Compose compiler | 1.5.8 | `composeOptions.kotlinCompilerExtensionVersion` |
| NDK | none — no native code in `:app` | n/a |
| Build command | `./gradlew :app:assembleRelease -PfdroidBuild=true` | this file |
| Output APK | `app/build/outputs/apk/release/app-release-unsigned.apk` | Gradle convention |

## Dependency surface (audited for F-Droid acceptance)

No Google Play Services, no Firebase, no FCM, no SafetyNet, no Play
Integrity, no Maps, no proprietary SDKs. Per CLAUDE.md O56 ("LineageOS /
de-Googled-Android compatibility — sustaining audit"). Build-time
`com.google.devtools.ksp` is OK — Kotlin Symbol Processing, runs at build,
not at runtime.

Notable runtime deps and their licenses:
- AndroidX, Compose, Lifecycle, Navigation — Apache 2.0
- Room — Apache 2.0
- Koin DI — Apache 2.0 (used instead of Hilt to avoid Google runtime coupling)
- BouncyCastle (crypto) — MIT-style
- kotlinx coroutines + serialization — Apache 2.0
- Jazzer (test-only) — Apache 2.0

## Reproducibility status

**Goal:** any reviewer can run the build command above and produce a byte-
identical APK to the one shipped via GitHub releases (which is what
F-Droid's reproducibility comparison checks).

**Known non-deterministic inputs (to address before submission):**
- Build timestamps in APK manifest. F-Droid setBuildTimestamp pattern
  applies; needs `androidResources.noCompress` audit.
- Gradle daemon state. CI runs with `--no-daemon` (see `.github/workflows/
  ci.yml`); document this in the F-Droid metadata.
- Compose compiler version pinning verified above; Kotlin metadata version
  must match between the F-Droid build server's toolchain and the release
  build.

**Not yet wired:**
- Gradle dependency locking (`dependencyLocking { lockAllConfigurations() }`).
  Locks every transitive version so the build is byte-stable against
  upstream version bumps. Should land before the first F-Droid submission.
- A CI job that does a second build and compares hash-of-APK. Needs the
  above dependency locking to be useful.

## F-Droid submission checklist

When ready to submit (not yet — versionCode is still 1):

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Tag the release: `git tag v0.x.y && git push --tags`. The
   `release-check.yml` workflow enforces monotonic versionCode.
3. Open an RFP at https://gitlab.com/fdroid/rfp/-/issues with:
   - Repo URL
   - Tag name
   - This `docs/FDROID_BUILD.md` link
   - License (TODO: file `LICENSE` in repo root — currently missing)
4. Once the RFP is accepted, an fdroiddata MR will be opened against the
   `Builds:` block; mirror this file's values into the YAML.

## Open prerequisites before first submission

- [ ] Add `LICENSE` file in repo root (probably GPL-3.0-or-later or AGPL-3.0)
- [ ] Wire Gradle dependency locking and commit `gradle.lockfile`s
- [ ] Verify reproducible-build hash matches across two CI runs
- [ ] Populate `fastlane/metadata/android/en-US/` (title, short_description,
      full_description, changelogs) — F-Droid reads these directly
- [ ] Screenshot set under `fastlane/metadata/android/en-US/images/`
- [ ] Confirm `release-check.yml` will catch versionCode regressions on the
      release tag pattern (G7)
