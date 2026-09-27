# CLAUDE.md — Expense Tracker

Personal + family expense tracker (Flutter, mobile). Offline-first: Drift is the
local source of truth, synced in the background to Supabase. Sign-in required.

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
- **AI parsing**: Google Gemini free tier, model `gemini-2.0-flash`, called from
  the Supabase Edge Function `parse-expense` (key is a function secret; signed-in
  users only). Free-tier prompts may be used by Google for training (note in Settings).
- **Voice**: on-device STT (`speech_to_text`), transcript fed to the AI parser.
- **Database**: Drift (SQLite) — typed queries for filters/search/insights.
- **State**: Riverpod (`flutter_riverpod`).

## Stack

| Concern | Package |
|---|---|
| State | `flutter_riverpod` |
| DB | `drift`, `sqlite3_flutter_libs`, `drift_flutter`, `uuid` (dev: `drift_dev`, `build_runner`) |
| Backend / auth / AI | `supabase_flutter` (Edge Function `supabase/functions/parse-expense`) |
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
  main.dart               // Supabase init + ProviderScope + auth gate
  app/theme.dart          // colors, text styles, ThemeData
  app/shell.dart          // bottom nav + FAB + IndexedStack
  data/db/database.dart   // Drift @DriftDatabase + DAOs
  data/db/tables.dart     // Categories, Expenses (Synced mixin: UUID, owner/family, dirty)
  data/settings_store.dart
  services/ai_parser.dart      // parse-expense fn -> {item, amount, category}
  services/speech_service.dart
  services/category_matcher.dart  // reuse / create / Boshqa + request
  services/sync_service.dart      // Drift <-> Supabase
  services/family_service.dart    // family RPCs
  services/csv_export.dart
  providers/providers.dart
  features/home|add|activity|insights|settings|auth|family/
  features/common/        // shared widgets
supabase/
  migrations/             // schema, RLS, RPCs
  functions/parse-expense/
  tests/family_rls_check.sql
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
- **Server only**: families, family_members(role), invites(email), category_requests.

## Conventions

- Money = `int`; format with `intl` grouping.
- Category match is name-normalized (trim+lowercase) → reuse or create; no dupes.
- AI returns strict JSON; validate, fall back to Manual on failure.
- Queries filter `deletedAt IS NULL`; writes set `dirty` + `updatedAt`.
- Family access rules live in RLS / SECURITY DEFINER RPCs, not client code.
- Lazy: no one-impl interfaces, no codegen beyond Drift, reuse `fl_chart`.

## Commands

- Codegen (after DB changes): `dart run build_runner build --delete-conflicting-outputs`
- Run: `flutter run`
- Analyze: `flutter analyze`
- Test: `flutter test`

## Workflow

One branch + PR to `main` per plan step. Plans live in `~/.claude/plans/`
(family plan: `tell-me-the-advantages-curried-cerf.md`, done).
