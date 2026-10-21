---
name: supabase-backend
description: Use when changing Xarajatlar's Supabase backend — SQL migrations, RLS/RPCs, family rules, Edge Functions (parse-expense, notify), or the FCM push pipeline.
---

# Supabase backend — Xarajatlar

Project `xgxygopxnchdobuooyzw` (Postgres + RLS). There is **one** project: debug and release apps both use it, so
every migration hits production data. No local Supabase stack; the `supabase` CLI is not installed here. The
Supabase plugin/MCP is enabled in `.claude/settings.json` (`apply_migration`, `execute_sql`, `deploy_edge_function`).

## Migrations
- Files: `supabase/migrations/YYYYMMDDHHMMSS_<slug>.sql`. Never edit one that's applied — add a new file
  (review fixes land as their own migration, e.g. `…_review.sql`).
- Old APKs stay installed in the family: keep legacy columns and RPCs working (same signatures, e.g.
  `transfer_admin`, `is_private` forced false by a trigger, `profiles.default_private`). Drop only once no APK needs it.
- Access rules live here (RLS + `SECURITY DEFINER` RPCs with `set search_path = ''`), never only in the client.
  Internal helpers go in schema `private` with execute revoked from `public, anon, authenticated`.
- After any rule change run `supabase/tests/family_rls_check.sql` (SQL editor or MCP `execute_sql`). It always
  ends in an exception so everything rolls back: `ALL_TESTS_PASSED` = green, any other message = the failed assert.
  Extend it for new rules.
- Client mirrors: Room has its own hand-written migrations (`data/AppDatabase.kt`); sync JSON mapping is in
  `services/SyncService.kt` (`applyCategories`/`applyExpenses`/`applyContributions`, `*.toJson`).

## Family rules (enforced server-side)
- One family per user. Any number of admins (`20261007090000_multiple_admins.sql`); admins promote/demote via
  `set_role`; the last admin must hand over before leaving while others remain; the last member leaving closes
  the family. Admin can delete the family.
- Admin invites by email (in-app Accept/Decline via `my_invites` / `accept_invite`; no email sent), removes members,
  sets members' titles (`set_title`; members can't set even their own).
- Admins can't edit others' expenses. An ex-member's shared expenses stay as read-only history (`frozen`).
- No private expenses since 2.1.0: every member's expense is shared and counts in family spending.
- Budget is personal only. Family spending = members' `shared_total` from `family_summary`; transfers never count.
- Categories are per user; others' category name/colour reach the Oila feed via `family_expenses_since`.
- Server-only tables: `families`, `family_members(role)`, `invites(email)`, `device_tokens`,
  `private.push_outbox`. (`needs`, `category_requests` dropped in 2.3.0.)

## Edge Functions
- `parse-expense`: expense text → Gemini (`gemini-3.6-flash`, free tier) → raw reply; the app validates it with
  `parseGeminiJson`. Secret `GEMINI_API_KEY`. JWT verified by the gateway and anon tokens rejected in code.
  Free-tier prompts may be used by Google for training (noted in Settings).
- `notify`: `verify_jwt = false` (pinned in `supabase/config.toml`; keep it off on every deploy). It only receives
  an outbox id and claims the row with the service role, then sends FCM HTTP v1 with secret `FCM_SERVICE_ACCOUNT`.
- Deploy: MCP `deploy_edge_function`, or `supabase functions deploy <name>` (CLI reads `config.toml`).

## Push pipeline
DB trigger → `private.push(users, data)` → outbox row + `pg_net` → `notify` → FCM (project `xarajatlar-app`).
- Devices register in `device_tokens` (own rows; `register_push_token` moves a phone's token to whoever signs in;
  sign-out deletes it).
- Data-only messages. The app (`services/Push.kt`) localizes text, drops pushes for another account (`uid`
  mismatch), and picks channel + tap route by `type` (currently only `expense`).
