# CLAUDE.md — Expense Tracker

Personal + family expense tracker (Flutter, mobile). Offline-first: Drift is the
local source of truth, synced in the background to Firebase (Firestore). Sign-in required.

## Core idea

Three ways to add an expense:
- **Type** — write "what I bought + how much"; AI derives item, amount, category.
- **Speak** — say it; on-device STT → transcript → same AI parse.
- **Manual** — fill amount / description / category / date by hand.

AI auto-creates categories when none match (in a family, non-admins get
**Boshqa** + a category request instead). Plus: dashboard, insights (charts),
search, category filters, and the **Oila** (family) tab.

## Locked decisions

- **Backend**: Firebase project `xarajatlar-app` (Blaze plan), Firestore and
  Cloud Functions in `europe-west3`. Migrated from Supabase (2026-09-27, fresh
  start, no data export). Client access rules in `firebase/firestore.rules`;
  anything touching other members' docs is a Cloud Function (Admin SDK).
- **Auth**: Firebase email + password. Confirm and reset via Firebase's emailed
  **links** (no codes). App is locked until signed in **and** email verified.
  On first sign-in the owner's local data is uploaded.
- **Sync**: each member writes only their own docs; newest `updatedAt` wins
  (rules refuse stale writes). Push dirty rows in batches, pull by the
  server-stamped `syncedAt`; runs on start, after writes, and every 30s after
  a failure. Firestore's offline cache is off (Drift is the offline store).
  Multi-device per user allowed.
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
  (`familySummary` function) so raw budgets and private amounts never leave.
- **AI parsing**: Firebase AI Logic (Gemini Developer API, free tier), model
  `gemini-3.1-flash-lite`, called from the app (`firebase_ai`). No App Check
  yet: add before public release. Free-tier prompts may be used by Google for
  training (note in Settings).
- **Voice**: on-device STT (`speech_to_text`), transcript fed to the AI parser.
- **Database**: Drift (SQLite) — typed queries for filters/search/insights.
- **State**: Riverpod (`flutter_riverpod`).

## Stack

| Concern | Package |
|---|---|
| State | `flutter_riverpod` |
| DB | `drift`, `sqlite3_flutter_libs`, `drift_flutter`, `uuid` (dev: `drift_dev`, `build_runner`) |
| Backend / auth / AI | `firebase_core`, `firebase_auth`, `cloud_firestore`, `cloud_functions`, `firebase_ai` |
| Voice | `speech_to_text`, `permission_handler` |
| Prefs | `shared_preferences` |
| Formatting | `intl` |
| Charts | `fl_chart` |
| Export | `csv`, `share_plus` |

## Design tokens

Semantic colors live in `AppColors` (a `ThemeExtension` in `lib/app/theme.dart`)
with light and dark sets; read them via `context.colors.x`, never hardcode.
Dark mode follows the system. Radii via `AppRadii`.
Category colors are stored per category row (hex): Food `#E08A5B`, Groceries
`#6FA86A`, Shopping `#C07FA6`, Transport `#5B8DB8`, Bills `#D9A24E`.
Currency is **UZS only** (whole units, no decimals). UI language is Uzbek.
Four tabs (Asosiy, Tarix, Tahlil, Oila) + FAB; Settings opens from the Home gear.

## Layout

```
lib/
  main.dart               // Firebase init + ProviderScope + auth gate
  firebase_options.dart   // flutterfire configure output
  app/theme.dart          // colors, text styles, ThemeData
  app/shell.dart          // bottom nav + FAB + IndexedStack
  data/db/database.dart   // Drift @DriftDatabase + DAOs
  data/db/tables.dart     // Categories, Expenses (Synced mixin: UUID, owner/family, dirty)
  data/settings_store.dart
  services/ai_parser.dart      // Firebase AI Logic -> {item, amount, category}
  services/speech_service.dart
  services/category_matcher.dart  // reuse / create / Boshqa + request
  services/sync_service.dart      // Drift <-> Firestore
  services/family_service.dart    // family Cloud Functions + invites
  services/csv_export.dart
  providers/providers.dart
  features/home|add|activity|insights|settings|auth|family/
  features/common/        // shared widgets
firebase/
  firestore.rules         // client access (own docs; family via Functions)
  firestore.indexes.json
functions/src/            // TypeScript v2 callables + onExpenseWritten trigger
  index.ts                // Firestore I/O, transactions, HttpsError codes
  logic.ts                // pure family rules (join/freeze/summary), `npm test`
```

## Data model

Synced tables share: id (UUID, client-generated), ownerId, familyId, updatedAt,
deletedAt (soft delete), dirty (local only).
- **Category**: name (unique per scope, case-insensitive), iconKey, colorHex,
  isArchived. Seed: Food & dining, Groceries, Shopping, Transport, Bills. AI adds more.
- **Expense**: description, amount (int, UZS), categoryId (FK), date,
  source (`typed`/`voice`/`manual`), rawInput?, isPrivate, pendingCategory?
  (awaiting admin approval), frozen (ex-member history), createdAt.
- **Settings** (local prefs): monthlyBudget + defaultPrivate (synced to
  `profiles`), sttLocale, lastAddMode.
- **Server only** (Firestore): profiles, families, members (doc id = uid,
  role), invites (id `familyId_email`), categoryRequests. Synced docs carry a
  server-stamped `syncedAt` for the pull cursor.

## Conventions

- Money = `int`; format with `intl` grouping.
- Category match is name-normalized (trim+lowercase) → reuse or create; no dupes.
- AI returns strict JSON; validate, fall back to Manual on failure.
- Queries filter `deletedAt IS NULL`; writes set `dirty` + `updatedAt`.
- Family access rules live in `firestore.rules` and Cloud Functions, not client code.
- No emulators (memory-constrained machine): test Functions logic with
  `npm --prefix functions test`, rules against the real project.
- Lazy: no one-impl interfaces, no codegen beyond Drift, reuse `fl_chart`.

## Commands

- Codegen (after DB changes): `dart run build_runner build --delete-conflicting-outputs`
- Run: `flutter run`
- Analyze: `flutter analyze`
- Test: `flutter test`
- Functions: `npm --prefix functions test`; deploy `firebase deploy --only functions`
  (rules: `--only firestore`)

## Workflow

One branch + PR to `main` per plan step. Plans live in `~/.claude/plans/`
(family plan: `tell-me-the-advantages-curried-cerf.md`, done; Firebase
migration: `firebase-migration.md`, done).
