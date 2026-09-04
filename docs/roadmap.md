# Revamp عيادتي / 3yadty into a multi-tenant SaaS

## Context

The product today is one file: `index_original.html`, 9,144 lines, ~1 MB. It is an Arabic RTL PWA with two engines glued together — an imperative `innerHTML` SPA (lines 325–3566) hosting a React inventory sub-app compiled in the browser by babel-standalone (lines 3575–9144). It talks straight from the browser to a Neon PostgREST endpoint, where the *entire* database is one table, `app_data (key, value)`, with entities encoded as key-prefixed JSON blobs.

That architecture cannot be sold as SaaS: no tenancy, no real auth (plaintext passwords inside a `config` blob), no referential integrity, and all of inventory living in a single row that concurrent writers clobber.

The reverse-engineering and data work is already done and merged on branch `db-migration-scripts`:

- `docs/use_cases/UC-001…UC-009` — nine use-case specs, all marked *Implemented*
- `docs/business_rules.md` — BR-G01…BR-G30, traced back to their source UCs
- `docs/entity_model.md`, `docs/data_dictionary.md`, `docs/glossary.md`
- `db/src/main/resources/db/migration/V1__…V10__.sql` — the full relational schema: shared-schema multi-tenancy on `clinic_id`, Postgres RLS as a second line of defence, and business-rule triggers (frozen-month immutability, return ceiling, append-only ledgers, cross-tenant FK guard)
- `db/src/test/resources/schema_checks.sql` + `seed_two_clinics.sql` — self-asserting SQL, passing on a fresh instance

What does **not** exist yet is the application. There is no `pom.xml`, no source tree, no build of any kind. This plan covers building it.

**Outcome:** a running Spring Boot + Vaadin Flow application at `apps/api`, multi-tenant, that reproduces UC-001…UC-009 with the legacy Arabic RTL look preserved, with every business rule BR-G01…BR-G30 covered by a named test.

## Decisions

Settled with the user before planning:

| Area | Decision |
|---|---|
| UI | **Vaadin Flow** (server-side Java). No Hilla, no JS build. |
| Tenancy | One shared host. Login, then a clinic picker when the user has >1 active membership. Session holds `clinic_id`. |
| Auth | In-app Spring Security form login against `app_user.password_hash`, **Argon2** hashing. |
| Scope | Build UC-001…UC-009 against the existing V1–V10 schema. Legacy behaviours the UC docs missed become a written, schema-sketched backlog — see *Feature gap backlog* below. |
| Three exceptions to that scope line | Three backlog items are named inside in-scope UC steps, so they need a small V11 migration rather than deferral: **gamification — weekly/monthly goals, badges, streaks** (UC-002 step 6 configures the thresholds, UC-005 steps 3 and 4 display them, so this one is not optional); a **working-day/holiday calendar** (UC-003 A3 counts a missed *scheduled work day* as absent, and BR-G29 paces the volume target by working days elapsed — neither is expressible from `clinic_settings.working_days_per_month`, a bare integer); and the **prep template library** (UC-006 step 2 and A1). Decide at the start of the phase that owns each whether to build it or to amend the UC doc to drop it. |
| Layout | Single Maven module `apps/api`; `db/` moves into it. UI kept separate from backend by **Spring Modulith** module boundaries, verified by a test. |
| Slicing | Vertical, one phase per use case. Each phase ends demoable. |
| Testing | Testcontainers Postgres integration tests, Karibu Vaadin view tests, TDD per BR-Gxx, a few Playwright e2e smokes. |
| Offline mode | Dropped. UC-001 A2 (offline queuing) is marked *not applicable* — it does not exist in a server-rendered app. |
| Locale | Arabic RTL only, kept verbatim from the legacy labels; strings in an i18n bundle so English can be added later. |
| Login identifier | V11 adds `app_user.username citext unique`; the login field stays `اسم المستخدم` as in the legacy screen. `email` becomes nullable and is used only for password reset and invites. `app_user_credentials_lookup` gets a username-keyed sibling. |
| Attachments | S3-compatible object storage (MinIO in dev). No base64 in the DB. |
| Legacy data | Greenfield. No backfill from `app_data`. |

## Local development and testing

Everything runs against Docker containers locally — no dependency on the legacy Neon instance for the new stack. `index_original.html`'s embedded credentials and its live data are out of scope for this work: not touched, not rotated, not migrated from.

- Dev: `docker-compose.yml` (Postgres + MinIO) — see Phase 0.
- Tests: Testcontainers spins up its own disposable Postgres per run; no shared or persistent dev database required.

## Phase 0 detail — scaffolding

Versions verified against Maven Central and the Vaadin docs during planning, not recalled:

| Component | Version | Note |
|---|---|---|
| Java | 25 (LTS) | Vaadin 25 needs 21+; 25 is newer LTS, fully compatible. |
| Vaadin Flow | **25.2.6** | Needs Spring Boot 4.1+, Java 21+ (confirmed against the Vaadin docs' compatibility table). Non-commercial tier. |
| Spring Boot | **4.1.0** GA | Confirmed present on Maven Central; matches Vaadin 25's requirement. |
| Spring Modulith | **2.1.1** GA | Current stable release, compiled against the Boot 4.0/4.1 line. (`get_latest_version` surfaced `2.2.0-M1`, a milestone for Boot 4.1 that has since gone GA-track — pin the GA `2.1.1`, not the milestone, and re-check before Phase 0 lands in case `2.2.0` has GA'd by then.) |
| jOOQ | 3.21.7 | Open-source edition is fine — Postgres is a supported dialect. |
| Flyway | `flyway-database-postgresql` 13.5.0 | |
| springdoc | `springdoc-openapi-starter-webmvc-ui` 3.1.0 | Verify it declares Boot 4 support at the pinned version before locking the POM — check at Phase 0 start. |
| Testcontainers | 1.21.4 | |
| Karibu | `karibu-testing-v10` 2.7.2 | Same artifact across Vaadin versions — 2.6.x+ supports Vaadin 25+, no separate `-v25` package. |
| jOOQ codegen | `testcontainers-jooq-codegen-maven-plugin` 0.0.4 | Runs Flyway against a throwaway Postgres container, then generates. Preferred over pointing codegen at a hand-maintained dev DB: no developer has to have a database up to build, and generated code can never drift from the migrations. |

**Verify all of the above again at the start of Phase 0** — this stack moves fast; a library only a minor version behind Boot 4.1 (springdoc, Testcontainers, the jOOQ codegen plugin) is the most likely thing to have shifted.

Work in this phase:

0. Copy this plan to `docs/roadmap.md`, commit it, and mark Phase 0 `in progress` in its status table — the first action of the whole build.
1. `git mv db/src/main/resources/db apps/api/src/main/resources/db`; the two SQL fixtures to `apps/api/src/test/resources/db/`.
2. `apps/api/pom.xml` — dependencies above, Vaadin production profile, codegen bound to `generate-sources`. Generated jOOQ sources go to `target/generated-sources/jooq` and are **not** committed.
3. Root package: `com.clinicos` — product name **ClinicOS**.
4. Spring Modulith module layout, one module per schema area rather than per table: `identity`, `clinicconfig`, `staff`, `evaluation`, `academy`, `prep`, `inventory`, `procedures`, `shared`. Each exposes an `api` package and hides `internal`. The UI is a single `ui` module declaring `@ApplicationModule(allowedDependencies = {…api modules})`, so a view reaching into an internal fails the build. Generated jOOQ code sits in a technical package excluded from module verification — every module needs it and it has no business meaning.
5. `ApplicationModules.of(Application.class).verify()` as a test.
6. **Tenant context.** `clinic_id` lives in the Vaadin session, copied onto the Spring `SecurityContext` at clinic selection. A jOOQ `ExecuteListener` is the wrong place — it fires per query, not per transaction, and cannot guarantee ordering against the transaction's first statement. Use a `Connection`-level hook (a `DataSource` decorator issuing `SET LOCAL app.clinic_id` when the connection is enlisted, or a `TransactionSynchronization` on `beforeCommit`/begin) so the GUC is set exactly once, first, per transaction. **Its failure mode is the reason this matters: an unset GUC does not error — RLS simply matches nothing and every query returns empty.** So the decorator must throw when no tenant is bound, and Phase 9 must assert that behaviour.
7. **The two RLS gaps.** Login happens before `clinic_id` exists, so it cannot run on the tenant connection. Corrected during Phase 1: rather than a second privileged `DataSource`/role, extend the `SECURITY DEFINER` function pattern V9 already established — `app_user_credentials_lookup` bypasses RLS safely today, so a username-keyed sibling plus `app_user_memberships_lookup` (for the clinic picker) are the sanctioned pre-tenant reads, reached through a narrow `TenantContext` auth-mode escape that binds the nil UUID instead of skipping tenant scoping entirely. One boundary to audit, no second role/pool/password to manage. Migration V11 adds `app_user.username citext unique` and both functions.
8. `Argon2PasswordEncoder` — Spring Security's `defaultsForSpringSecurity_v5_8()` parameters (m=16384, t=2, p=1) unless you have a reason to raise them; benchmark on the target host and raise `m` until a hash costs ~0.5–1 s.
9. `docker-compose.yml`: Postgres + MinIO. Note that V9 creates `app_rw` **without a password** — the compose file must `alter role app_rw password …` after migration, and prod does the same from a privileged connection. It does not belong in a migration.
10. Testcontainers base class: migrate as the superuser, then reconnect as `app_rw` for assertions; a helper to set the tenant GUC; `schema_checks.sql` executed as part of `verify`.
11. `docs/backlog/legacy-gaps.md` written from the gap table below.
12. `CLAUDE.md` at repo root: stack, module layout, how to run migrations/codegen/tests/app locally, the tenant-context rule (`SET LOCAL app.clinic_id` — never skip it), and a pointer to `docs/roadmap.md` for status. Keep both current after every slice — see the update discipline in memory (`project-update-docs-after-slices`).



## Progress tracking

This plan is copied to `docs/roadmap.md` at the start of Phase 0 and committed — git-tracked, not a session-local file, so any session cloning the repo sees current status. The status table below is the source of truth for "what's done"; a session picking up work reads it first.

**Rule:** the commit that finishes a phase flips its row from `in progress` to `done` in the same commit — status and the work it describes never drift apart. A session starting work sets its phase to `in progress` before the first commit of that phase.

| Phase | Status | Notes |
|---|---|---|
| 0 — Scaffolding | done | |
| 1 — UC-001 Login | in progress | |
| 2 — UC-002 Employees/roles | not started | |
| 3 — UC-003 Daily work/attendance | not started | |
| 4 — UC-004/005 Evaluation | not started | |
| 5 — UC-006 Prep checklists | not started | |
| 6 — UC-007 Academy | not started | |
| 7a–7d — UC-008 Inventory | not started | |
| 8 — UC-009 Admin dashboard | not started | |
| 9 — Hardening/release | not started | |

## Phases

Every phase ends with: green `mvn verify`, a screen you can click through, and an `aiup-vaadin-jooq:uc-coverage` audit run against the UC it implements. On completion, update this file's status table and re-commit `docs/roadmap.md`.

### Phase 0 — Scaffolding

See *Phase 0 detail* above. Ends when `mvn verify` passes on an app that boots, connects as `app_rw`, serves a Vaadin route, and whose Modulith verification test is green.

### Phase 1 — UC-001 Log in and access the system

BR-G01, BR-G02, BR-G03.

- **Done:** `V11__auth_username.sql` — `app_user.username citext unique` (email now nullable, reset/invites only), `grant select (username) on app_user to app_rw` (the column-level grant from V9 does not auto-cover new columns), `app_user_credentials_lookup_by_username` and `app_user_memberships_lookup` as `SECURITY DEFINER` siblings of the existing email-keyed lookup, hardened the same way (`search_path = pg_catalog, public, pg_temp`, schema-qualified relations). See roadmap.md:76 (step 7, corrected) for why this replaced the originally-planned second privileged `DataSource`.
- **Done:** `TenantContext.enterAuthMode()`/`exitAuthMode()` + `TenantConnectionListener` bind the nil UUID in auth mode instead of throwing, so the two functions above can run before a clinic is selected while every other RLS-scoped table still matches zero rows. Covered by `AuthModeTenantEscapeIT`.
- **Done, found during this work (not planned):** `TenantConnectionListener`'s "no tenant bound" check moved from `afterBegin` to `beforeBegin`. Throwing from `afterBegin` — the ORIGINAL Phase 0 behavior — leaks Spring's transaction-active state and the bound connection forever on the thread once a second IT class exercises it, silently breaking every later transaction (no exception, RLS-scoped queries just return empty). `beforeBegin` runs before `doBegin`, which Spring does guard with cleanup. Also fixed: `AbstractPostgresIntegrationTest`'s shared static Postgres container is now a true JVM-wide singleton (manual `.start()`, not `@Testcontainers`/`@Container`), since that annotation pair scopes stop/start per subclass and was killing the container between IT classes.
- **Done:** `V12__seed_permissions.sql` — the `permission` table is empty as of V1 (which seeds only the four roles). Seeded both permission families and the legacy default `role_permission` sets:
  - Main-app codes (legacy `MAIN`, `index_original.html:3477`): `emp`, `quick`, `ceo`, `tasksTab`, `acadVerify`, `acadEdit`.
  - Inventory area codes (legacy `INV`, 19 of them): `tray, issue, procs, myprocs, manage, orders, receive, returns, suppliers, dash, profit, analytics, waste, doctors, supAnalysis, received, itemAnalysis, approvals, ledger` — seeded now even though Phase 7 consumes them, so the `الصلاحيات` matrix in Phase 2 has something to render.
  - Defaults (legacy `DEFA`/`DEFM`, L3479–3485): `assistant` → main `{emp}` + areas `{tray, issue, procs, myprocs, manage}`; `receptionist` → main `{emp}` + areas `{orders, receive, returns, suppliers, ledger}`; `manager` → main `{quick, ceo, tasksTab, acadVerify, acadEdit, emp}` + all areas; `owner` → everything (BR-G03).
- **Remaining:** Login view (`/login`) reproducing `renderLogin()`: `اسم المستخدم`, `كلمة المرور`, `دخول`, error `❌ اسم المستخدم أو كلمة المرور غير صحيحة` (same message for unknown user, bad password, and inactive account — never disclose which). Argon2 verify against the V11 lookup. Inactive account rejected (BR-G01) on **both** `app_user.status` and `membership.status`.
- **Remaining:** Clinic picker view when the user has >1 active membership; skipped for one. Tenant context bound on selection; login's `activity_log` row written only here (needs both `clinic_id` and `actor_membership_id`, neither of which exist before this point).
- **Remaining:** App shell: the legacy right-side off-canvas drawer (`.side`, `transform:translateX(100%)`), sticky topbar with tab title + `عيادتي · إدارة الأداء` + Arabic long date, footer user block with `🚪` logout. Theme: port the legacy CSS custom properties verbatim — `--teal:#1f5a52`, `--teal2:#2e7d6f`, `--mint:#eaf5f1`, `--accent:#2e9e84`, `--bg:#f4f7f8`, `--line:#e1e8e6`, `--ink:#16201d`; Almarai + Tajawal from Google Fonts; 18px card radius; `dir="rtl"`.
- **Remaining:** Navigation entries shown/hidden from `role_permission` + `membership_permission` (BR-G02); owner sees all (BR-G03).
- **Remaining, added to scope during this review (not in the original plan):** UC-001 A3 — reopen the last-visited section on return, if still permitted for the (possibly changed) role. Persist as a browser cookie, not the Vaadin session (dies at logout, so it can't satisfy "on this device") and not a DB column (would wrongly follow the user across devices).

UC-001 A2 (offline) is out of scope by decision; record that in the UC doc. A3 and the login `activity_log` write are in scope (added above) — not deviations.

### Phase 2 — UC-002 Manage employees and roles

BR-G04, BR-G05 (unlock), BR-G06, BR-G07.

Admin subtabs `الإعدادات` (employees, evaluation weights summing to 100, working days, attendance rules) and `المستخدمون` (accounts, password change, activate/suspend, link account↔employee). Tables: `employee`, `clinic_settings`, `evaluation_weight`, `incentive_tier`, `membership`, `membership_permission`, `performance_override`.

Also the owner-only `الصلاحيات` matrix — the main-app permission keys map onto `permission` rows; the 19 inventory area keys arrive with Phase 7 but the grid is built here.

### Phase 3 — UC-003 Record daily work and attendance

BR-G08, BR-G09, BR-G10, BR-G11.

`تسجيل الموظف`: check-in/check-out (`الحضور` / `الانصراف`, statuses `في الميعاد` / `متأخّر` / `بعد الدوام` / `انصراف مبكّر`), grace-period logic, tasks grouped by dimension (فني / سلوكي / مبادرة) with frequency pills, photo-proof upload to object storage, tasks locked until check-in. Tables: `self_check`, `daily_record`, `daily_task_completion`, `task_definition`, `attachment`.

Gamification hero (`#eHero` — level ring, streaks, badges, weekly goal) is backlog, not this phase.

### Phase 4 — UC-004 + UC-005 Evaluate, verify, and view my evaluation

BR-G05, BR-G12, BR-G13, BR-G14, BR-G15, BR-G16, BR-G17, and the admin-dashboard scoring half of BR-G29.

The hardest phase — the scoring engine lives here.

- `تقييم وتحقّق المدير`: date strip, employee chooser, per-day technical/behavioural rating, task approve/reject with reason, assigned tasks (`task_assignment`) approve/reject, auto-save.
- Scoring service: weighted blend of six categories from `evaluation_weight`; a no-data category is *excluded*, not zeroed, and coverage is reported (BR-G15); manual override is a floor, never a ceiling (BR-G06); on-time > late > undone for assignments (BR-G13).
- Freeze/unlock: past months read `evaluation_snapshot`; the V10 triggers already refuse writes to a frozen snapshot and its components — the service must surface that as a clean error, not a 500.
- `تقييمي` (UC-005): read-only, own record only (BR-G16), closed months only; owner has no evaluation (BR-G17).

**In-scope gap:** `daily_task_completion` has `done`, `completed_at`, `photo_id` — but nowhere to record the manager's per-task verdict. The legacy `daily:` record carries a `reviewed` map (✓ / ✗ plus a return reason), which is what UC-004 step 2 is describing and what feeds the dashboard's `تغطية المراجعة` supervision metric. Add `review_status` + `review_reason` + `reviewed_by` / `reviewed_at` to `daily_task_completion` in this phase's V-migration.

Give this phase its own BR-by-BR test class before any UI work.

### Phase 5 — UC-006 Prepare and run procedure checklists

BR-G18, BR-G19, BR-G20. `prep_checklist` → `prep_section` → `prep_item`, `prep_run` → `prep_run_item`. Approval gate before a checklist is usable; at least one section with one item; run progress tracked per day. The built-in template library (`📚 قوالب جاهزة`) is backlog.

### Phase 6 — UC-007 Complete onboarding academy training

BR-G21, BR-G22, BR-G23, BR-G24. `academy_unit`, `academy_question`, `academy_step_submission`, `academy_exam_attempt`. Curriculum = shared core + role units; practical steps need verifier-confirmed photos; exam pool drawn only from covered units; certificate derived from a passing attempt, printed via `window.print()` and the legacy `@media print` rules.

**One in-scope gap here:** `academy_unit` has only `title`, `applies_to`, `display_order`, `requires_photo` — there is nowhere to store the unit's actual teaching content, which the legacy app renders as sections, bullet points, and image/video hints. UC-007 step 2 ("works through each curriculum unit") is unbuildable without it. Add it in this phase's V-migration, either as `academy_unit.content jsonb` or as `academy_section` + `academy_point` tables; decide when the screen is designed.

Legacy extras — quiz kinds (`فهم` / `قرار` / `اكتشف الغلط`), trainer daily rating, the `تقرير القرار` decision report — go to backlog.

### Phase 7 — UC-008 Inventory and procedure costs

BR-G25, BR-G26, BR-G27, BR-G28. The largest phase; the legacy React module is ~4,900 lines across 19 views. Split it:

- **7a Foundation** — `supplier`, `inventory_item`, `stock_movement` append-only ledger; on-hand is `sum(qty_delta)` per `(item_id, location)`, where `location` is the `location_kind` enum (`store` / `tray`), not a table. Views `جرد الأصناف`, `التراي`, `المخزن` (issue), `السجل`. `stock_movement` is revoked for UPDATE/DELETE by a V10 trigger — corrections are compensating rows, and the service layer must be written that way from the start.
- **7b Purchasing** — `purchase_order` + lines, `النواقص والطلب`, `الاستلام` (with mandatory invoice photo), `المرتجعات` with the V10 return-ceiling trigger surfaced as a user error, `الموردين`.
- **7c Procedures and costing** — `procedure`, `procedure_bom`, `procedure_case`, `procedure_case_item` with unit cost frozen at issue time (BR-G28); `قوائم الإجراءات`, `سجل إجراءاتي`.
- **7d Approvals and analytics** — `inventory_change_request` queue (BR-G26), `الاعتمادات`, `الربحية`, `تحليل الأصناف`, `الهدر`, `الأطباء`, `تحليل الموردين`. Note the schema's queue is narrower than the legacy screen: `change_request_kind` is `edit | delete` against an `item_id`, while `الاعتمادات` groups six categories (new procedure-case records, case edits, new procedures, procedure edits, deletions, the item queue). BR-G26 names only stock-affecting item edits/deletes and supplier returns, so the schema satisfies the rule — widening the queue to the other four is a backlog item, decided when this sub-phase starts.

Role scoping (BR-G25) rides on the permission model built in Phase 2.

### Phase 8 — UC-009 Admin dashboard and analytics

BR-G29, BR-G30. `نظرة عامة` (monthly invoice total against a pace-adjusted target — scaled to elapsed working days, not the full month), employee cards with score ring and tier, team summary, supervision metrics; `ملف الموظف` with component bars, trend chart and the print-to-PDF report; `سجل النشاط` from `activity_log`. Manager/owner only.

### Phase 9 — Hardening and release

OpenAPI surface reviewed via springdoc, Playwright smokes green, `schema_checks.sql` wired into `mvn verify`, RLS negative tests (a request without the GUC must fail loudly, not return an empty result), rate limiting on login, activity logging on every mutating action, deployment.

## Appendix — the legacy scoring algorithm

Extracted from `index_original.html:1200–1261` and its helpers (`freqDays` L877, `dimOfTask` L881, `satOf` L994, `workDaysInMonthToDate` L997, `paceTargetFor` L1001, `effShift` L1011). Phase 4 is a reimplementation of exactly this. Read it before writing the scoring service — several parts are counter-intuitive.

- **Per-task completion rate**, by frequency: *daily* = days-done ÷ days-logged; *weekly* = distinct Saturday-weeks done ÷ weeks tracked; *monthly* = 100 if done once, else 0; *custom* = 100 if done, else 0 only when the last approved run is older than `every × unit` days, otherwise `null` (not yet due). Archived tasks and tasks created after the month are excluded entirely.
- **إنجاز المهام** = mean of the non-null per-task rates.
- **The technical / behavioural / initiative components are NOT the manager's 1–5 daily ratings.** They are the mean of the per-task completion rates of the tasks *classified under that dimension* (`byDim`, L1216–1220). The manager's `fanni` / `solooki` / `ibda3` scores from `daily_record` feed only the trend sparkline (L1257). This is easy to get wrong, and `daily_record.fanni/solooki/ibda3` in the schema invites getting it wrong — implement `byDim` and cover it with a test that would fail if the 1–5 ratings leaked into the final score.
- **Assigned tasks** blend into إنجاز المهام, not into their own component: approved and on time = 100, approved and late = 50, otherwise 0; averaged, then averaged again with the completion figure (L1222–1224). This is where BR-G13 actually lives.
- **Initiative**: `min(approved self-proposals ÷ 3 × 100, 100)`, then averaged with the dimension-derived `ibda3`. After 3+ logged days, having zero self-proposals scores 0 rather than counting as no-data — a deliberate choice, not a bug (L1226–1234).
- **حجم التشغيل** = `min(actual ÷ paceTarget, 1) × 100`, where `paceTarget = monthlyTarget × (working days elapsed ÷ working days total)` (BR-G29). Null when no target is configured.
- **الالتزام بالمواعيد**: per working day, arrival 100 (or 50 if later than `shift start + grace`), departure 100 (or 50 if earlier than `shift end − grace`, or 50 when no check-out); the day scores their mean. Check-in after shift end = 0. No check-in on a working day = 0. Mean across working days.
- **Override is a floor**: `max(auto, override)`, and it supplies the value outright when auto is null (L1242).
- **Final score** = weighted mean over *assessed* components only, renormalised by the assessed weights (`Σ v·w ÷ Σ w`). Coverage = assessed weight ÷ total weight. A null component is excluded, never zeroed (BR-G15).
- **Incentive** = `maxIncentive × tier.pct`; total pay = base + incentive.
- **Freeze**: on the first read of a closed month, the computed result is written once to the snapshot and every later read returns the snapshot (L1200, L1260).

Legacy defaults, to seed a new clinic (`index_original.html:900`, `:927`):

| Setting | Value |
|---|---|
| `incentive_tier` | ≥90 `ممتاز` 100 % · ≥75 `جيد جدًا` 75 % · ≥60 `جيد` 50 % · else `يحتاج تطوير` 0 % |
| `evaluation_weight` | completion 18, fanni 18, solooki 12, ibda3 22, volume 18, attendance 12 (sums to 100) |
| `clinic_settings` | shift 09:00–17:00, grace 15 min, volume target 20 000 EGP, working days Sat–Thu |

## Feature gap backlog

Found in `index_original.html` but absent from both the UC docs and the V1–V10 schema. Record as `docs/backlog/legacy-gaps.md` during Phase 0, one entry each with the schema delta sketched. Not built in this plan.

| Legacy feature | Schema delta needed |
|---|---|
| Gamification: level ring, streaks + record, badges, weekly goals, per-scope visibility toggles | `gamification_settings`, `badge_threshold`, `weekly_goal` |
| Working-days calendar and holidays (clinic-wide or per-employee) | `clinic_holiday`, weekday mask on `clinic_settings` — today only `working_days_per_month integer` |
| Geofence attendance (clinic coords + allowed radius) | lat/lng/radius on `clinic_settings` |
| Manager daily note (private or shown to employee), rejection reason on a returned task | columns on `daily_record` / `daily_task_completion`. The `🔔` nudge itself is *not* a gap — `notification` already covers it. |
| Doctor as an entity (per-doctor analytics) | `doctor` table — today `procedure_case.doctor_name text` |
| Supplier rating, lead days, price map | columns on `supplier`, plus `supplier_item_price` |
| Lot number, delivery cost on receipt | columns on `purchase_order_line` |
| Prep template library, importable into a clinic | platform-level `prep_template` |
| Academy quiz kinds, trainer daily rating, settings toggles, decision report | columns on `academy_question`, `academy_settings` |
| Image storage policy, manual/auto purge, JSON backup + restore | retention policy on `attachment`, export job |
| Reservations / holds on tray stock | `stock_hold` |
| "Essential item" flag on a prep checklist item (legacy shows `N أساسي`) | `prep_item.essential boolean` |

## Cross-cutting engineering notes

These apply to every phase; decide them once in Phase 0 rather than per-screen.

- **Trigger errors are English `raise exception` text with SQLSTATE `P0001`** (frozen snapshot, return ceiling, append-only ledger, cross-tenant reference). Build one exception translator that maps them to Arabic user-facing messages, and cover each mapping with a test. Without it these surface as raw 500s.
- **`app_rw` is created by V9 as `create role app_rw noinherit login` with no password.** Setting its password is an operational step outside Flyway (`alter role app_rw password …` from a privileged connection, or trust/peer auth locally). Handle it in `docker-compose` and document it for prod; do not add it to a migration where it would be committed in plaintext.
- **The `permission` table has no seed rows.** V1 seeds the four roles only. Phase 1 must add a migration seeding the permission codes and the legacy default `role_permission` sets, plus the 19 inventory area codes ahead of Phase 7.
- **Legacy `تقييمي` showed closed months only; UC-005 step 2 says "current monthly score".** Follow the UC — show the live current month too — and note the deviation in the UC doc.
- **Corrections to `stock_movement` and `supplier_return_line` are compensating rows, never updates.** The V10 triggers enforce it; write the services that way from the outset rather than discovering it at Phase 7.

## Verification

Per phase:

1. `mvn -pl apps/api verify` — unit, Karibu view, and Testcontainers integration tests.
2. `ApplicationModules.of(Application.class).verify()` — proves the UI module has not reached into backend internals.
3. `aiup-vaadin-jooq:uc-coverage` against the phase's UC — maps every main-scenario step, alternative flow and business rule onto code and tests, and reports the gaps.
4. Manual click-through against the corresponding legacy screen, side by side, for visual fidelity.

Whole-system, before release:

1. `flyway migrate` twice from clean — idempotent, zero errors.
2. `schema_checks.sql` as `app_rw` — RLS isolation, frozen-snapshot immutability, return ceiling, cross-tenant FK guard, `password_hash` unreachable.
3. `seed_two_clinics.sql` then log in as a member of clinic A and confirm no clinic-B row is reachable through any screen.
4. A request whose transaction never set `app.clinic_id` must **fail**, not return zero rows. Assert this explicitly — a silent empty result is the dangerous failure mode of RLS.
5. Playwright: login → check-in → complete a task → manager evaluates → issue stock → dashboard.
6. Every BR-G01…BR-G30 has at least one test naming it. Assert the count.
