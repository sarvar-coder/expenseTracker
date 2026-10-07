# CLAUDE.md — Expense Tracker

Personal + family expense tracker (native Android, Kotlin + Jetpack Compose;
migrated from Flutter in 2.0.0). Offline-first: Room is the local source of truth, synced in the background to Supabase. Sign-in required.

## Core idea

Three ways to add an expense:
- **Type** — write "what I bought + how much"; AI derives item, amount, category.
- **Speak** — say it; on-device STT → transcript → same AI parse.
- **Manual** — fill amount / description / category / date by hand.

AI auto-creates categories when none match (in a family, non-admins get
**Boshqa** + a category request instead). Plus: dashboard, insights (charts),
search, category filters, and the **Oila** (family) tab.

## Locked decisions

- **Backend**: Supabase project `xgxygopxnchdobuooyzw` (Postgres + RLS). Chosen
  over Firebase: relational data, family rules fit RLS, SQL aggregates.
- **Auth**: email + password, email confirmation ON with **6-digit codes** (no
  deep links) for confirm and reset. Whole app is locked behind the session.
  On first sign-in the owner's local data is uploaded.
- **Sync**: each member writes only their own rows; newest `updatedAt` wins.
  Push dirty rows, pull by `updated_at`; runs on start, after writes, and
  every 30s after a failure. Multi-device per user allowed.
- **Family**: one family per user, one admin. Admin invites by email (in-app
  Accept/Decline, no email sent), removes members, approves/rejects category
  requests, renames/archives categories, transfers admin, deletes the family.
  Admin can't edit others' expenses or see private ones; must transfer admin
  before leaving; last member leaving deletes the family. Ex-member's shared
  expenses stay in family history as read-only (`frozen`).
- **Categories** are shared per family; only the admin creates them directly.
  On join, same-name categories merge; unmatched ones go to Boshqa + requests.
- **Private toggle** per expense (default in Settings, initially shared).
  Private = invisible to the family and excluded from family spending.
- **Budget**: personal budget per member. Family contribution =
  `budget − own private spending this month` (may go negative; no budget → 0).
  Family budget = sum of contributions, computed server-side
  (`family_summary` RPC) so raw budgets and private amounts never leave.
- **AI parsing**: Google Gemini free tier, model `gemini-3.6-flash`, called from
  the Supabase Edge Function `parse-expense` (key is a function secret; signed-in
  users only). Free-tier prompts may be used by Google for training (note in Settings).
- **Voice**: on-device STT (platform `SpeechRecognizer`), transcript fed to the AI parser.
- **Database**: Room (SQLite, KSP) — Flow queries for filters/search/insights.
- **State**: `ViewModel`/`remember` + `StateFlow`; manual DI via one `AppContainer` (no Hilt).
- **Distribution**: family-only signed APK on GitHub Releases, same `applicationId`
  `com.sarvarbek.expense_tracker` + keystore as the old Flutter app.

## Stack

| Concern | Pick |
|---|---|
| UI | Jetpack Compose + Material3, single Activity, Navigation-Compose |
| DB | Room (KSP), epoch-millis `Long` timestamps, `Long` amounts |
| Backend / auth / AI | supabase-kt (`auth`, `postgrest`, `functions`) + ktor okhttp, kotlinx.serialization |
| Voice | `SpeechRecognizer`, `RECORD_AUDIO` via ActivityResult |
| Prefs | `SharedPreferences` (`settings` file) |
| Formatting | `DecimalFormat` space grouping, java.time (desugared) |
| Charts | Compose `Canvas` (no chart lib) |
| Export | hand-written CSV + `FileProvider` + `ACTION_SEND` |
| Tests | JUnit4 + Robolectric + Compose UI test (JVM, no emulator) |

AGP 9 (built-in Kotlin), Kotlin 2.4, compileSdk 37, minSdk 24, JDK 17.

## Design tokens

Semantic colors live in `AppColors` (`ui/theme/Theme.kt`, via CompositionLocal)
with light and dark sets; read them via the theme, never hardcode. Shared
component wrappers in `ui/theme/Components.kt`, shared widgets in `ui/common/`.
Dark mode follows the system. Radii via `AppRadii`.
Category colors are stored per category row (hex): Food `#E08A5B`, Groceries
`#6FA86A`, Shopping `#C07FA6`, Transport `#5B8DB8`, Bills `#D9A24E`.
Currency is **UZS only** (whole units, no decimals). UI language is Uzbek.
Four tabs (Asosiy, Tarix, Tahlil, Oila) + FAB; Settings opens from the Home gear.

## Layout

```
app/src/main/java/com/sarvarbek/expense_tracker/
  App.kt                  // Application + AppContainer (manual DI)
  MainActivity.kt
  ui/AppRoot.kt           // AuthGate + Routes (nav graph)
  ui/Shell.kt             // bottom nav + FAB + tab pages
  ui/theme/               // AppColors, type scale, AppRadii, component wrappers
  ui/common/              // Format.kt (money/dates), Widgets.kt (shared widgets)
  data/                   // Room entities, AppDatabase + DAO, SettingsStore
  services/               // AiParser, CategoryMatcher, SyncService, FamilyService,
                          // SpeechService, CsvExport
  features/home|add|activity|insights|settings|auth|family/
app/src/test/             // Robolectric + Compose tests
supabase/
  migrations/             // schema, RLS, RPCs
  functions/parse-expense/
  tests/family_rls_check.sql
docs/                     // privacy.html, font licence
```

## Data model

Synced tables share: id (UUID, client-generated), ownerId, familyId, updatedAt,
deletedAt (soft delete), dirty (local only).
- **Category**: name (unique per scope, case-insensitive), iconKey, colorHex,
  isArchived. Seed: Food & dining, Groceries, Shopping, Transport, Bills. AI adds more.
- **Expense**: description, amount (int, UZS), categoryId (FK), date,
  source (`typed`/`voice`/`manual`), rawInput?, isPrivate, pendingCategory?
  (awaiting admin approval), frozen (ex-member history), createdAt.
- **Settings** (SharedPreferences): monthlyBudget + defaultPrivate (synced to
  `profiles`), sttLocale, lastAddMode.
- **Server only**: families, family_members(role), invites(email), category_requests.

## Conventions

- Money = `int`; format with `intl` grouping.
- Category match is name-normalized (trim+lowercase) → reuse or create; no dupes.
- AI returns strict JSON; validate, fall back to Manual on failure.
- Queries filter `deletedAt IS NULL`; writes set `dirty` + `updatedAt`.
- Family access rules live in RLS / SECURITY DEFINER RPCs, not client code.
- Lazy: no one-impl interfaces, no codegen beyond Room/KSP, no chart library.

## Commands

Use JDK 17: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
(`java_home -v 17` finds nothing). Never run emulators.

- Test + lint: `./gradlew :app:testDebugUnitTest :app:lintDebug`
- Debug APK (`.dev` suffix, installs beside the real app): `./gradlew assembleDebug`
- Release APK: `./gradlew assembleRelease` (signs with `key.properties` at repo root,
  gitignored; keystore `~/upload-keystore.jks`). Check: `apksigner verify --print-certs` → CN=Sarvarbek.
- Install on the attached phone: `~/Library/Android/sdk/platform-tools/adb install -r <apk>`
- Release: bump `versionCode`/`versionName` in `app/build.gradle.kts`,
  `gh release create vX.Y.Z Xarajatlar-X.Y.Z.apk`.

## Workflow

One branch + PR to `main` per plan step. Plans live in `~/.claude/plans/`
(family plan: `tell-me-the-advantages-curried-cerf.md`, done).
