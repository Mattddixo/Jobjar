# Releasing Job Jar

## 1. Bump the version

In `app/build.gradle.kts`, increase `versionCode` by 1 (Play rejects a code it has seen
before) and set `versionName` to what users should see, e.g. `1.3`.

## 2. Signing (one-time setup)

Create an upload key once and keep it somewhere safe (password manager plus an offline copy):

```bash
keytool -genkeypair -v -keystore jobjar-upload.jks -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```

Then create `keystore.properties` in the project root (both files are git-ignored):

```properties
storeFile=jobjar-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Enrol in **Play App Signing** when creating the app in Play Console: Google holds the actual
app signing key, and the upload key can be reset through Google if you lose it.

## 3. Build

```bash
./gradlew testDebugUnitTest lintRelease bundleRelease
```

The signed bundle is `app/build/outputs/bundle/release/app-release.aab`. Release builds are
shrunk with R8, so install the release build on a phone and go through every screen before
uploading. Shrinking problems only show up in release builds.

Before the first upload of a new database version, also run the migration tests on a device or
emulator: `./gradlew connectedDebugAndroidTest`. Commit the new file Room writes to
`app/schemas/` alongside the migration.

## 4. Play Console checklist (first release)

- **Privacy policy URL:** host `docs/PRIVACY.md` somewhere public (e.g. GitHub Pages, or the
  file's own GitHub URL if the repository is public) and paste the link.
- **Data safety form:** no data collected, no data shared; data isn't encrypted in transit
  because it's never transmitted; users can request deletion by deleting jobs/uninstalling.
- **Permissions declaration:** Calendar (read + write): "Adds jobs the user schedules to their
  calendar, and checks an event belongs to the app before changing it."
- **Content rating:** complete the questionnaire (utility app, no user-generated content shared).
- **Target audience:** 13+ (or 18+), not designed for children.
- **Store listing:** short and full description, icon (`docs/play-icon-512.png`), feature
  graphic (1024×500), at least 2 phone screenshots.
- Start with an **internal testing** track, then closed testing, before production. New personal
  developer accounts must run a closed test with testers for 14 days before production access.
