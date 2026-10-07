# Xarajatlar

Personal + family expense tracker for Android (Kotlin, Jetpack Compose), in Uzbek.
Add expenses by typing, speaking or by hand; AI picks the item, amount and category.
Offline-first (Room) with background sync to Supabase; families share categories
and spending.

## Build

Needs JDK 17 and the Android SDK.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug   # tests + lint
./gradlew assembleDebug                           # debug APK (installs as "Xarajatlar dev")
./gradlew assembleRelease                         # signed release APK (needs key.properties)
```

Backend (schema, RLS, RPCs, the `parse-expense` Edge Function) lives in `supabase/`.
See `CLAUDE.md` for architecture and decisions.
