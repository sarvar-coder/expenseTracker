---
name: release
description: Use when cutting a Xarajatlar release — version bump, signed release APK, GitHub Release.
---

# Release — Xarajatlar

Distribution: family-only signed APK on GitHub Releases. Same `applicationId` `com.sarvarbek.expense_tracker` and
keystore as the old Flutter app, so it installs as an update.

## Steps
1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`; commit `chore(release): X.Y.Z` on its own
   branch + PR.
2. Gate: `./gradlew :app:testDebugUnitTest :app:lintDebug`.
3. `./gradlew assembleRelease` — signs with `key.properties` at repo root (gitignored; keystore
   `~/upload-keystore.jks`). Without `key.properties` it silently falls back to debug signing.
4. Check: `$ANDROID_HOME/build-tools/<ver>/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
   → `CN=Sarvarbek`.
5. `cp app/build/outputs/apk/release/app-release.apk Xarajatlar-X.Y.Z.apk` (repo root, gitignored), then
   `gh release create vX.Y.Z Xarajatlar-X.Y.Z.apk`.
6. Install on the attached phone (updates the real app): `adb install -r Xarajatlar-X.Y.Z.apk` — only when asked.

## Gotchas
- No R8 (`isMinifyEnabled = false`).
- Ship backend migrations before the APK that needs them; older APKs must keep working against the new schema.
- `BuildConfig.VERSION_NAME` is reported with the push token (`register_push_token` `p_version`) — handy to see
  which phones still run old APKs.
