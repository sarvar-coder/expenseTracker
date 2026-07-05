# CLAUDE.md — Expense Tracker

Single-user personal expense tracker (Flutter, mobile). Offline-first, no auth,
one user (the owner). Full design reference:
`~/Downloads/expense_tracker_flutter_mockup.html`.

## Core idea

Three ways to add an expense:
- **Type** — write "what I bought + how much"; AI derives item, amount, category.
- **Speak** — say it; on-device STT → transcript → same AI parse.
- **Manual** — fill amount / description / category / date by hand.

AI auto-creates categories when none match. Plus: dashboard, insights (charts),
search, category filters.

## Locked decisions

- **AI parsing**: Google Gemini free tier — `google_generative_ai`, model
  `gemini-2.0-flash`. Free. Free-tier prompts may be used by Google for training
  (surfaced as a note in Settings).
- **Voice**: on-device STT (`speech_to_text`), transcript fed to the AI parser.
- **Database**: Drift (SQLite) — typed queries for filters/search/insights.
- **State**: Riverpod (`flutter_riverpod`).

## Stack

| Concern | Package |
|---|---|
| State | `flutter_riverpod` |
| DB | `drift`, `sqlite3_flutter_libs`, `drift_flutter` (dev: `drift_dev`, `build_runner`) |
| AI | `google_generative_ai` |
| Voice | `speech_to_text`, `permission_handler` |
| Secure key | `flutter_secure_storage` |
| Prefs | `shared_preferences` |
| Formatting | `intl` |
| Charts | `fl_chart` |
| Export | `csv`, `share_plus` |

## Design tokens

Accent `#1C7A5E` · hero `#123328` · bg `#F5F3EE` · card `#FFFFFF` · border
`#EAE7E0` · text `#1B1A17` · muted `#8C8880`.
Category colors: Food `#E08A5B`, Groceries `#6FA86A`, Shopping `#C07FA6`,
Transport `#5B8DB8`, Bills `#D9A24E`.
Currency default **UZS** (whole units, no decimals). Default monthly budget
4 000 000. Rounded cards, pill chips, segmented controls, bottom nav + center FAB.

## Layout

```
lib/
  main.dart               // ProviderScope + MaterialApp + theme
  app/theme.dart          // colors, text styles, ThemeData
  app/shell.dart          // bottom nav + center FAB + IndexedStack
  data/db/database.dart   // Drift @DriftDatabase + DAOs
  data/db/tables.dart     // Categories, Expenses
  data/settings_store.dart
  services/ai_parser.dart      // Gemini -> {item, amount, category}
  services/speech_service.dart
  services/category_matcher.dart
  services/csv_export.dart
  providers/              // Riverpod providers
  features/home|add|activity|insights|settings/
  features/common/        // shared widgets
```

## Data model

- **Category**: id, name (unique, case-insensitive), iconKey, colorHex, isArchived.
  Seed: Food & dining, Groceries, Shopping, Transport, Bills. AI adds more.
- **Expense**: id, description, amount (int, UZS), categoryId (FK), date,
  source (`typed`/`voice`/`manual`), rawInput (nullable), createdAt.
- **Settings**: currencyCode, monthlyBudget, geminiApiKey (secure), sttLocale.

## Conventions

- Money = `int`; format with `intl` grouping.
- Category match is name-normalized (trim+lowercase) → reuse or create; no dupes.
- AI returns strict JSON; validate, fall back to Manual on failure.
- Lazy: no one-impl interfaces, no codegen beyond Drift, reuse `fl_chart`.

## Commands

- Codegen (after DB changes): `dart run build_runner build --delete-conflicting-outputs`
- Run: `flutter run`
- Analyze: `flutter analyze`

## Build order

13 approval-gated steps in `~/.claude/plans/fizzy-chasing-cookie.md`. Data before
UI; Manual-add before AI so there's testable data early. Do one step, wait for
approval, then next.
```
1 Scaffold+deps+theme   2 Drift DB+seed    3 Settings+Riverpod
4 App shell/nav         5 Home dashboard   6 Add: Manual
7 AI parser+matcher     8 Add: Type        9 Add: Speak
10 Activity/search      11 Insights        12 Settings screen
13 Polish
```
