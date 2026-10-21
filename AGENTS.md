# Project Memory — Xarajatlar (Expense Tracker)

Personal + family expense tracker, native Android (Kotlin, Jetpack Compose; migrated from Flutter in 2.0.0), UI in
Uzbek. Offline-first: Room is the local source of truth, synced in the background to Supabase. Sign-in required.

`CLAUDE.md` is a symlink to this file — edit `AGENTS.md`.

## Repo layout
```
app/src/main/java/com/sarvarbek/expense_tracker/
  App.kt          Application + AppContainer (manual DI, Supabase client)   MainActivity.kt
  ui/AppRoot.kt   AuthGate + Routes (nav graph)      ui/Shell.kt  bottom nav + FAB + tab pages
  ui/theme/       AppColors, type scale, AppRadii, Components.kt wrappers
  ui/common/      Format.kt (money/dates), I18n.kt, Widgets.kt (shared widgets)
  data/           Room entities, AppDatabase + DAO + hand-written migrations, SettingsStore
  services/       AiParser, CategoryMatcher, SyncService, FamilyService, SpeechService, CsvExport, Push (FCM)
  features/       home | add | activity | insights | settings | auth | family
app/src/main/assets/i18n/  uz.json (Latin) + uz_cyrl.json (Cyrillic), same keys — all UI strings
app/src/test/     Robolectric + Compose UI tests (JVM)
supabase/         migrations/ (schema, RLS, RPCs), functions/parse-expense, functions/notify, tests/ — see skill
docs/             privacy.html, Manrope font licence
```
- Don't touch: `build/`, `app/build/`, `.gradle/`, `.kotlin/`, `.idea/`, `local.properties`, `key.properties`
  (signing secrets), `/*.apk` (release APKs copied out for GitHub Releases), `app/google-services.json`.
- Plans live in `~/.claude/plans/` (family plan: `tell-me-the-advantages-curried-cerf.md`, done).

## Terminology
UI words are Uzbek; use them for the features they name.
- **Type / Speak / Manual** — the three add modes (`ExpenseSource` `typed`/`voice`/`manual`). Type and Speak go
  through the AI parser (Speak first via on-device STT); AI auto-creates a category when none match.
- **Asosiy / Tahlil / Muhokama / Oila** — Home / Insights / Discuss (blank for now) / Family tabs, plus the FAB.
  Settings opens from the Home gear; **Turkumlar** = categories in Settings.
- **Oila** — the family tab: family spending only (month total, members' spending, category donut merged by
  normalized name, **Ro'yxat** list). Home and Tahlil show only own rows.
- **Hissa** — contributions (own `Contribution` rows: money in) plus transfers; the personal budget on Home.
- **Transfer** — an `Expense` with `transferTo` set. Never counts in family spending.
- **Available** — own contributions + transfers received − own spending − transfers given, per calendar month
  (`myAvailable`; mirrors `family_summary.available`). **Shortfall** = overspend beyond it, asked about on save.
- **Frozen** — an ex-member's shared expenses kept in family history, read-only.
- **Title** — a member's family title (Ota, Ona…), set only by an admin.
- **Dirty** — local-only flag: row not yet pushed.
- **Kerakli / needs / category requests** — dropped features (2.3.0); migrations remain in history.
- `is_private`, `pendingCategory`, `profiles.default_private` — legacy, always false/unused; kept for old APKs.

## Run & test
Use JDK 17: `export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` (`java_home -v 17`
finds nothing). Robolectric is pinned to `sdk=34` because SDK 35+ sandboxes need Java 21.
- Install: nothing beyond the SDK; Gradle fetches dependencies.
- Run locally: `./gradlew installDebug` — `.dev` suffix, "Xarajatlar dev", installs beside the real app; physical
  phone over adb only (**never use an emulator**). It talks to the **production** Supabase project.
- Unit/UI tests: `./gradlew :app:testDebugUnitTest`; one class:
  `./gradlew :app:testDebugUnitTest --tests 'com.sarvarbek.expense_tracker.HelpersTest'`.
- Test + lint before finishing: `./gradlew :app:testDebugUnitTest :app:lintDebug`.
- Build: `./gradlew assembleDebug`; signed release: `./gradlew assembleRelease` — see `.agents/skills/release`.
- E2E / on-device verification: see `.agents/skills/e2e-testing`.
- Backend (migrations, family rules, Edge Functions, RLS check, push pipeline): see `.agents/skills/supabase-backend`.

## Conventions
- Locked: Supabase over Firebase (relational data, family rules fit RLS, SQL aggregates); FCM only for push;
  Gemini via the `parse-expense` Edge Function; manual DI via `AppContainer` (no Hilt); `ViewModel`/`remember`
  + `StateFlow`; Navigation-Compose, single Activity.
- Auth: email + password; confirm and reset by 6-digit email codes (no deep links); whole app behind the session.
- Room: epoch-millis `Long` timestamps, `Long` amounts. Money is **UZS only**, whole units, formatted by
  `formatMoney` (`DecimalFormat`, space grouping). Dates via java.time (desugared, minSdk 24).
- Synced tables share: client-generated UUID `id`, `ownerId`, `familyId`, `updatedAt`, `deletedAt` (soft
  delete), local `dirty`. Queries filter `deletedAt IS NULL`; writes set `dirty` + `updatedAt`.
- Sync: each member writes only their own rows; newest `updatedAt` wins. Push dirty, pull by `updated_at`; runs on
  start, after writes, every 30 s after a failure. Multi-device per user. First sign-in uploads local data.
- Room DB changes: bump version + hand-written `Migration` in `AppDatabase.kt` (`exportSchema = false`; checked
  by `DataTest`). Never destructive.
- Family access rules live in RLS / SECURITY DEFINER RPCs, not client code. Old APKs stay in the wild: keep
  legacy columns/RPCs working rather than dropping them.
- Categories are per user; matching is name-normalized (trim + lowercase) → reuse or create, no dupes. Category
  colours are stored per row as 6-hex.
- AI returns strict JSON; validate (`parseGeminiJson`), fall back to Manual on failure.
- Colours via `AppColors` (light + dark, CompositionLocal), radii via `AppRadii`; never hardcode. Shared wrappers
  in `ui/theme/Components.kt`, shared widgets in `ui/common/`. Dark mode follows the system.
- Strings only via `t("key")` from `assets/i18n/*.json`; add every key to both `uz.json` and `uz_cyrl.json`.
- Prefs in `SharedPreferences` file `settings`; charts are Compose `Canvas` (no chart lib); CSV export is
  hand-written + `FileProvider` + `ACTION_SEND`.
- Lazy: no one-impl interfaces, no codegen beyond Room/KSP. Mark shortcuts with `// ponytail:`.
- Workflow: one branch + PR to `main` per plan step; commits `feat: …`, `fix: … (review)`, `chore(release): X.Y.Z`.

## Lessons learned
When the user corrects a mistake, add a one-line entry to Lessons learned. When a lesson recurs, promote it
to Conventions. Remove lessons that no longer apply. Format: `- YYYY-MM-DD <mistake> → <what to do instead>`.
