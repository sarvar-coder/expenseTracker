---
name: e2e-testing
description: Use when reproducing a bug on a real phone, verifying a fix end-to-end, or adding/running instrumented (androidTest) tests for Xarajatlar.
---

# E2E testing — Xarajatlar

**Never install or start an Android emulator.** Emulators eat disk and freeze this laptop. Physical phone only.

Current state: no `app/src/androidTest/`, no Maestro. UI and data flows are covered by Robolectric + Compose UI
tests on the JVM (`app/src/test`, `./gradlew :app:testDebugUnitTest`). No device attached by default. If you
couldn't run on a device, say "E2E not run" — never claim end-to-end verification.

## Setup (physical phone)
1. Phone: Settings → About → tap Build number 7× → Developer options → USB debugging (or Wireless debugging).
2. USB: plug in, accept the RSA prompt. Wireless: `adb pair <ip:port>` (pairing code), then `adb connect <ip:port>`.
3. `adb devices` must list the phone as `device` (not `unauthorized`).
   `adb` is `~/Library/Android/sdk/platform-tools/adb`.
4. `./gradlew installDebug` → `com.sarvarbek.expense_tracker.dev` ("Xarajatlar dev"), beside the real app.
   Launch: `adb shell monkey -p com.sarvarbek.expense_tracker.dev 1`.
5. Backend: there is no local Supabase — the dev app uses the **production** project. `AuthGate` needs a
   signed-in, email-verified account (6-digit code). Use a dedicated test account (ask the user which one); never
   sign in as, or write into, the user's real account or family. Pushes need Google Play services on the phone.

## Seed / reset data
- Fresh local state: `adb shell pm clear com.sarvarbek.expense_tracker.dev`. Server rows survive and sync back on
  sign-in — clean test data on the server side (soft delete via the app, or SQL for the test user only).
- No import path (no VIEW/SEND filters): seed by adding expenses in the app (Type / Speak / Manual), or locally via
  `adb shell run-as com.sarvarbek.expense_tracker.dev` on `databases/` (force-stop first). Rows need `dirty = 1`
  to push.
- Type mode and Speak call the `parse-expense` Edge Function (needs network + sign-in); Speak needs an on-device
  recognizer and `RECORD_AUDIO`.
- Family flows need two test accounts (admin + member), ideally two phones.
- Logs: `adb logcat --pid=$(adb shell pidof com.sarvarbek.expense_tracker.dev)`; sync logs under tag `sync`,
  push under `push`.

## Run tests
- JVM (current suite): all `./gradlew :app:testDebugUnitTest`; one class
  `./gradlew :app:testDebugUnitTest --tests 'com.sarvarbek.expense_tracker.HomeAddTest'`.
- Instrumented, first time only: add `androidx.test.ext:junit`, `androidx.test:runner` (Compose `ui-test-junit4`
  and `ui-test-manifest` are already there) and `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`;
  tests in `app/src/androidTest/java/com/sarvarbek/expense_tracker/`. Run all `./gradlew connectedDebugAndroidTest`,
  one `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.sarvarbek.expense_tracker.<Class>`.
  Prefer a Robolectric test when the bug doesn't need real network, STT or FCM.

## Bug-reproduction procedure
1. Write the user's steps down (tap by tap, which tab, which account/family role, online or offline).
2. Reproduce manually on the phone with the test account; capture logcat. Can't reproduce → stop, report what
   you tried.
3. Write a test that fails the same way (Robolectric/Compose in `app/src/test` first; androidTest if it needs a
   device). Server rule bugs → an assert in `supabase/tests/family_rls_check.sql`.
4. Fix at the root cause.
5. Rerun the test and the manual steps.
6. Report exactly how you verified (device model, account role, steps, test name).

## Gotchas
- Robolectric runs `sdk=34` (`app/src/test/resources/robolectric.properties`) — SDK 35+ needs Java 21.
- Sync retries every 30 s after a failure; wait or relaunch before concluding a row didn't sync.
