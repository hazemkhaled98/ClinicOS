# ClinicOS — Multi-tenant SaaS dental clinic staff-management app

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
| UI | ~~Vaadin Flow~~ → **Server-rendered Thymeleaf + HTMX + Alpine.js + Tailwind CSS** (migrated on branch `refactor/thymeleaf-htmx` after Phase 1 shipped; the Ui's purpose-built phase notes below predate and reference the Vaadin views they replaced). |
| Tenancy | One shared host. Login fixes the clinic scope — `(clinic_slug, username)` keys the account to exactly one clinic (BR-005), so there is no clinic picker; `TenantSessionBinder` primes the session with the clinic + permissions at request time. Session holds `clinic_id`. |
| Auth | In-app Spring Security form login against `app_user.password_hash`, **Argon2** hashing. |
| Scope | Build UC-001…UC-009 against the existing V1–V10 schema. Legacy behaviours the UC docs missed become a written, schema-sketched backlog — see *Feature gap backlog* below. |
| Three exceptions to that scope line | Three backlog items are named inside in-scope UC steps, so they need a small V11 migration rather than deferral: **gamification — weekly/monthly goals, badges, streaks** (UC-002 step 6 configures the thresholds, UC-005 steps 3 and 4 display them, so this one is not optional); a **working-day/holiday calendar** (UC-003 A3 counts a missed *scheduled work day* as absent, and BR-G29 paces the volume target by working days elapsed — neither is expressible from `clinic_settings.working_days_per_month`, a bare integer); and the **prep template library** (UC-006 step 2 and A1). Decide at the start of the phase that owns each whether to build it or to amend the UC doc to drop it. |
| Layout | Single Maven module `apps/api`; `db/` moves into it. UI kept separate from backend by **Spring Modulith** module boundaries, verified by a test. |
| Slicing | Vertical, one phase per use case. Each phase ends demoable. |
| Testing | Testcontainers Postgres integration tests, Playwright browser e2e, TDD per BR-Gxx. |
| Offline mode | Dropped. UC-001 A2 and UC-003 A5 (offline queuing) are marked *not applicable* — it does not exist in a server-rendered app. |
| Locale | Arabic RTL only, kept verbatim from the legacy labels; strings in an i18n bundle so English can be added later. |
| Login identifier | V11 added `app_user.username citext unique`; the login field stays `اسم المستخدم` as in the legacy screen. `email` becomes nullable and is used only for password reset and invites. V14 then made usernames **per-clinic** — `app_user.clinic_id NOT NULL` + `unique (clinic_id, username)` — and the credential lookup key is `(clinic_slug, username)`, so the same username can exist in different clinics. Login collects the clinic code (the clinic slug, shown after sign-up) alongside username/password. |
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
| UI | ~~Vaadin Flow 25.2.6~~ → Thymeleaf 3.1 + HTMX 2 + Alpine 3 + Tailwind CSS 4 | Post-Phase-1 migration. Needs Boot 4.1. No JS build. |
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

- [x] 0. Copy this plan to `docs/roadmap.md`, commit it, and mark Phase 0 `in progress` in its status table — the first action of the whole build.
- [x] 1. `git mv db/src/main/resources/db apps/api/src/main/resources/db`; the two SQL fixtures to `apps/api/src/test/resources/db/`.
- [x] 2. `apps/api/pom.xml` — dependencies above, Vaadin production profile, codegen bound to `generate-sources`. Generated jOOQ sources go to `target/generated-sources/jooq` and are **not** committed.
- [x] 3. Root package: `com.clinicos` — product name **ClinicOS**.
- [x] 4. Spring Modulith module layout, one module per schema area rather than per table: `identity`, `clinicconfig`, `staff`, `evaluation`, `academy`, `prep`, `inventory`, `procedures`, `shared`. Each exposes an `api` package and hides `internal`. The UI is a single `ui` module declaring `@ApplicationModule(allowedDependencies = {…api modules})`, so a view reaching into an internal fails the build. Generated jOOQ code sits in a technical package excluded from module verification — every module needs it and it has no business meaning.
- [x] 5. `ApplicationModules.of(Application.class).verify()` as a test.
- [x] 6. **Tenant context.** `clinic_id` lives in the Vaadin session, copied onto the Spring `SecurityContext` at clinic selection. A jOOQ `ExecuteListener` is the wrong place — it fires per query, not per transaction, and cannot guarantee ordering against the transaction's first statement. Use a `Connection`-level hook (a `DataSource` decorator issuing `SET LOCAL app.clinic_id` when the connection is enlisted, or a `TransactionSynchronization` on `beforeCommit`/begin) so the GUC is set exactly once, first, per transaction. **Its failure mode is the reason this matters: an unset GUC does not error — RLS simply matches nothing and every query returns empty.** So the decorator must throw when no tenant is bound, and Phase 9 must assert that behaviour.
- [x] 7. **The two RLS gaps.** Login happens before `clinic_id` exists, so it cannot run on the tenant connection. Corrected during Phase 1: rather than a second privileged `DataSource`/role, extend the `SECURITY DEFINER` function pattern V9 already established — `app_user_credentials_lookup` bypasses RLS safely today, so a username-keyed sibling plus `app_user_memberships_lookup` (for the clinic picker) are the sanctioned pre-tenant reads, reached through a narrow `TenantContext` auth-mode escape that binds the nil UUID instead of skipping tenant scoping entirely. One boundary to audit, no second role/pool/password to manage. Migration V11 adds `app_user.username citext unique` and both functions.
- [x] 8. `Argon2PasswordEncoder` — Spring Security's `defaultsForSpringSecurity_v5_8()` parameters (m=16384, t=2, p=1) unless you have a reason to raise them; benchmark on the target host and raise `m` until a hash costs ~0.5–1 s.
- [x] 9. `docker-compose.yml`: Postgres + MinIO. Note that V9 creates `app_rw` **without a password** — the compose file must `alter role app_rw password …` after migration, and prod does the same from a privileged connection. It does not belong in a migration.
- [x] 10. Testcontainers base class: migrate as the superuser, then reconnect as `app_rw` for assertions; a helper to set the tenant GUC; `schema_checks.sql` executed as part of `verify`.
- [x] 11. `docs/backlog/legacy-gaps.md` written from the gap table below.
- [x] 12. `CLAUDE.md` at repo root: stack, module layout, how to run migrations/codegen/tests/app locally, the tenant-context rule (`SET LOCAL app.clinic_id` — never skip it), and a pointer to `docs/roadmap.md` for status. Keep both current after every slice — see the update discipline in memory (`project-update-docs-after-slices`).



## Progress tracking

This plan is copied to `docs/roadmap.md` at the start of Phase 0 and committed — git-tracked, not a session-local file, so any session cloning the repo sees current status. The status table below is the source of truth for "what's done"; a session picking up work reads it first.

**Rule:** the commit that finishes a phase flips its row from `in progress` to `done` in the same commit — status and the work it describes never drift apart. A session starting work sets its phase to `in progress` before the first commit of that phase.

**Step-level checkpoint rule:** After completing each numbered step or bullet item, update its checkbox from `- [ ]` to `- [x]` in this file. Do this _before_ moving to the next step. If the session is interrupted, the next session reads this file first and resumes at the first unchecked box.

**Resume protocol:** On session start (new or resumed), read this file first. Find the current phase (the one marked `in progress`). Scan its steps for the first `- [ ]`. That is the resume point. Skip all `- [x]` steps — they are done.

| Phase | Status | Notes |
|---|---|---|
| 0 — Scaffolding | done | |
| 1 — UC-001 Login + Phase 1b sign-up | done | PR #5 merged; UI migrated to Thymeleaf/HTMX on `refactor/thymeleaf-htmx` (see Phase 1 migration note) |
| 1c — Design system reconciliation | done | DESIGN.md/tokens.css reconciled, shared components.css + /dev/styleguide, hygiene test guards added on branch `design/system-reconciliation` |
| 2 — UC-002 Employees/roles | done | Slices 2a–2e: V16 user-admin functions, V17 tenant-scoped role_permission, V18 gamification tables, service interfaces + implementations, UI controllers + templates, unit + IT tests · Slice 2f: users-tab cleanup — duplicate username/email renders a field error (no 500), add form is account-fields only (every user auto-becomes an employee), row role is read-only, role editing moved to the Settings employee row · Slice 2g `feat/uc002-role-hierarchy`: role hierarchy enforcement (BR-005, RoleRanks rank guard), A1 archive now unlinks + suspends the linked account to block login, manager scoping of employee/permission editing, actor-aware controllers/templates + tests; evaluation-engine units (A2/A3/BR-002/BR-003/BR-004, step-5 eval reflection) deferred to Phase 4 with UC-004/UC-005 |
| 3 — UC-003 Daily work/attendance | done | Slices 3a–3f: V20 work calendar (`clinic_settings.working_weekdays` weekday mask + `clinic_holiday` table), MinIO object storage + `AttachmentService`, staff attendance/daily-task/assignment services, `/employees` daily-work screen + photo/frequency task fields, admin work-calendar UI (weekday mask + holidays), A5 (offline recording) marked not applicable · Evaluation units (A1/A2/A3 scoring counts, step-8/Post-S-1 figure reflection) deferred to Phase 4 with UC-004/UC-005 — attendance is captured, labelled, and persisted only. PR #15 |
| 4 — UC-004/005 Evaluation | done | Slices 4a–4d on `feat/uc004-evaluation`: V22 task-review columns + staff `EvaluationInputService`; `ScoringEngine` (pure, BR-by-BR tested) + `DefaultEvaluationService` (lazy freeze-on-read, unlock/refreeze); manager `تقييم وتحقّق` review screen (task/assignment approve-reject, override, unlock) replacing the `quick-access` placeholder; employee `تقييمي` screen with gamification (weekly goals, streak, badges). Closes the deferred UC-002 evaluation-engine units (A2 floor overrides, A3 unlock/recalc, BR-002 freeze-write-once, BR-003 floor rule, BR-004 incentive-by-tier) and UC-003's deferred figure reflection. `/pr-sentinel` review + `/coverage-check` UC-004/UC-005 clean (manual-floor-override-on-closed-month template gap, proof-photo review test, real gamification-arithmetic test all closed). PR #17. |
| 5 — UC-006 Prep checklists | done | Slices on `feat/uc006-prep-checklists` (PR #18): prep checklist CRUD with approval gate (manager approval/unapprove, owner-without-employee approve path), sections/items management (Alpine.js-driven editor, template-library import from built-in catalog), per-day run tracking with toggle/reset, missing-employee actionable error. `/pr-sentinel` + `/coverage-check` UC-006 clean. |
| 6 — UC-007 Academy | done | PR #19 (squash `a87b752`) on `feat/uc007-academy`: V24 curriculum seed + template catalog, `DefaultAcademyService` (`curriculumFor` with role scoping + sequential unlock, photo submission/verification, exam pool from covered units, certificate, exact-question-set exam submission), tenant checks inside transactions; `AcademyController` + Thymeleaf views (writer/learner/verify/unit-editor/exam/certificate); `DefaultAcademyServiceIT` + writer/learner tests (photo FK guard, cross-tenant isolation, sequential unlock, editor-role gate). `/pr-sentinel` (design + ponytail + correctness) + `/coverage-check` UC-007 clean. Corrections in-PR: gate curriculum editor reads behind editor role (was answer leak), idempotent default-curriculum import. |
| 7a–7d — UC-008 Inventory | done | PR #21 merged (see Phase 7). V25 cross-tenant guards on inventory child tables; `InventoryService` + `DefaultInventoryService`; `InventoryController` + all 19 views (foundation screens 7a, purchasing 7b, procedures/costing 7c, approvals + analytics 7d). Role scoping rides the Phase 2 permission model (BR-G25); BR-G26 approval queue, BR-G27 return-ceiling (V10 trigger surfaced as a user error), BR-G28 unit-cost freeze. `/pr-sentinel` + `/coverage-check` clean; manual testing S1–S14 green — three in-PR defects fixed: supplier save binding, zero-qty-line 500 on orders/returns, delete-approval mislabel. |
| 8 — UC-009 Admin dashboard | in progress | |
| 9 — Hardening/release | not started | |

## Phases

Every phase ends with: green `mvn verify`, a screen you can click through, and an `aiup-vaadin-jooq:uc-coverage` audit run against the UC it implements. On completion, update this file's status table and re-commit `docs/roadmap.md`.

### Phase 0 — Scaffolding

See *Phase 0 detail* above. Ends when `mvn verify` passes on an app that boots, connects as `app_rw`, serves a Vaadin route, and whose Modulith verification test is green.

### Phase 1 — UC-001 Log in and access the system

BR-G01, BR-G02, BR-G03.

- [x] **Done:** `V11__auth_username.sql` — `app_user.username citext unique` (email now nullable, reset/invites only), `grant select (username) on app_user to app_rw` (the column-level grant from V9 does not auto-cover new columns), `app_user_credentials_lookup_by_username` and `app_user_memberships_lookup` as `SECURITY DEFINER` siblings of the existing email-keyed lookup, hardened the same way (`search_path = pg_catalog, public, pg_temp`, schema-qualified relations). See roadmap.md:76 (step 7, corrected) for why this replaced the originally-planned second privileged `DataSource`.
- [x] **Done:** `TenantContext.enterAuthMode()`/`exitAuthMode()` + `TenantConnectionListener` bind the nil UUID in auth mode instead of throwing, so the two functions above can run before a clinic is selected while every other RLS-scoped table still matches zero rows. Covered by `AuthModeTenantEscapeIT`.
- [x] **Done, found during this work (not planned):** `TenantConnectionListener`'s "no tenant bound" check moved from `afterBegin` to `beforeBegin`. Throwing from `afterBegin` — the ORIGINAL Phase 0 behavior — leaks Spring's transaction-active state and the bound connection forever on the thread once a second IT class exercises it, silently breaking every later transaction (no exception, RLS-scoped queries just return empty). `beforeBegin` runs before `doBegin`, which Spring does guard with cleanup. Also fixed: `AbstractPostgresIntegrationTest`'s shared static Postgres container is now a true JVM-wide singleton (manual `.start()`, not `@Testcontainers`/`@Container`), since that annotation pair scopes stop/start per subclass and was killing the container between IT classes.
- [x] **Done:** `V12__seed_permissions.sql` — the `permission` table is empty as of V1 (which seeds only the four roles). Seeded both permission families and the legacy default `role_permission` sets:
  - Main-app codes (legacy `MAIN`, `index_original.html:3477`): `emp`, `quick`, `ceo`, `tasksTab`, `acadVerify`, `acadEdit`.
  - Inventory area codes (legacy `INV`, 19 of them): `tray, issue, procs, myprocs, manage, orders, receive, returns, suppliers, dash, profit, analytics, waste, doctors, supAnalysis, received, itemAnalysis, approvals, ledger` — seeded now even though Phase 7 consumes them, so the `الصلاحيات` matrix in Phase 2 has something to render.
  - Defaults (legacy `DEFA`/`DEFM`, L3479–3485): `assistant` → main `{emp}` + areas `{tray, issue, procs, myprocs, manage}`; `receptionist` → main `{emp}` + areas `{orders, receive, returns, suppliers, ledger}`; `manager` → main `{quick, ceo, tasksTab, acadVerify, acadEdit, emp}` + all areas; `owner` → everything (BR-G03).
- [x] **Remaining:** Login view (`/login`) reproducing `renderLogin()`: `اسم المستخدم`, `كلمة المرور`, `دخول`, error `❌ اسم المستخدم أو كلمة المرور غير صحيحة` (same message for unknown user, bad password, and inactive account — never disclose which). Argon2 verify against the V11 lookup. Inactive account rejected (BR-G01) on **both** `app_user.status` and `membership.status`.
- [x] **Done:** Clinic picker removed — an account belongs to exactly one clinic under V14 (BR-005), so selection was dead weight. Session priming (membership lookup in auth mode → clinic bound → `activity_log` login row → role + permissions) moved into `TenantSessionBinder`, which runs it once per session at the start of the first Vaadin request; the picker route (`/select-clinic`) is gone and the root route forwards to the first permitted section via `BeforeEnterObserver.forwardTo` (`HelloView`). Covered by `TenantSessionBinderTest` (prime/re-lookup-skip/unauthenticated/bind/clear + auth-mode) and `HelloViewTest` (first-section forward, empty state).
- [x] **Remaining:** App shell: the legacy right-side off-canvas drawer (`.side`, `transform:translateX(100%)`), sticky topbar with tab title + `عيادتي · إدارة الأداء` + Arabic long date, footer user block with `🚪` logout. Theme: port the legacy CSS custom properties verbatim — `--teal:#1f5a52`, `--teal2:#2e7d6f`, `--mint:#eaf5f1`, `--accent:#2e9e84`, `--bg:#f4f7f8`, `--line:#e1e8e6`, `--ink:#16201d`; Almarai + Tajawal from Google Fonts; 18px card radius; `dir="rtl"`.
- [x] **Remaining:** Navigation entries shown/hidden from `role_permission` + `membership_permission` (BR-G02); owner sees all (BR-G03).
- [x] **Done:** `PermissionsService` (identity :: api) + `DefaultPermissionsService` (identity :: internal) — effective codes = `role_permission` ∪ `membership_permission granted=true` − `granted=false`, `owner` returns the full catalog regardless (BR-G03). Resolved into the Vaadin session at clinic selection (`SESSION_ROLE_CODE`, `SESSION_PERMISSIONS`), read by `MainLayout` via `NavSectionResolver` (legacy rules: owner hides `تقييمي` and dashboard supersedes `المهام`; `prep` unconditional; academy needs `acadVerify`/`acadEdit`; inventory on any INV code). Sections are clickable placeholders (`SectionPlaceholderView`, 8 routes). Also fixed a latent runtime bug: the picker's pre-clinic `findByUserId` now runs in auth mode, else the real login→picker transaction threw "No tenant bound". Covered by `NavSectionResolverTest` (9), `MainLayoutTest` nav (3), `TenantSessionBinderTest` (perm session assertions), `PermissionsServiceIT` (grant/revoke/owner-override/cross-tenant RLS). `mvn verify`: 27 unit + 19 IT green.
- [x] **Remaining, added to scope during this review (not in the original plan):** UC-001 A3 — reopen the last-visited section on return, if still permitted for the (possibly changed) role. Persist as a browser cookie, not the Vaadin session (dies at logout, so it can't satisfy "on this device") and not a DB column (would wrongly follow the user across devices).
- [x] **Done:** `SectionPlaceholderView` writes a `lastSection` cookie (30-day, path `/`) on every section open; `HelloView` reads it and reopens that route when the role still permits it (A3), else lands on the first permitted section of the (possibly changed) role (step 6). Fallback heading when a session has no permissions. Logout cuts access to nav and sections: session attrs + Spring Security context cleared, drawer renders empty. Covered by `HelloViewTest` (6: `resolveTarget` no-cookie/permitted-cookie/denied-cookie/empty-role/legacy-owner, first-section navigation, fallback heading), `MainLayoutTest` post-logout nav test. `mvn verify`: 36 unit + 19 IT green.

UC-001 A2 (offline) is out of scope by decision; record that in the UC doc. A3 and the login `activity_log` write are in scope (added above) — not deviations.

### Phase 1b — self-service sign-up

Reopens Phase 1. Spec: `docs/superpowers/specs/2026-09-05-uc001-signup-design.md` (Approved). Zero DDL — the schema already supports it (`clinic.status` defaults `trial`, owner role seeded, V12 grants owner everything); the only blocker was `app_rw`'s missing INSERT rights, solved with one `SECURITY DEFINER` function (V9 explicitly anticipated it).

- [x] **V13:** `signup_clinic_with_owner(...)` — one volatile `SECURITY DEFINER` function hardened like the V11 pair (`search_path = pg_catalog, public, pg_temp`, schema-qualified, `revoke all from public`, `grant execute to app_rw`). Inserts clinic → app_user → owner membership atomically, returns all three ids. Unique violations surface as SQLSTATE 23505 for Java mapping.
- [x] **identity :: api:** `SignupService` — `SignupResult signUp(SignupRequest)`, records `SignupResult`/`SignupRequest`, `SignupConflictException(Field { USERNAME, EMAIL, CLINIC_SLUG })`.
- [x] **identity :: internal:** `DefaultSignupService` — Argon2 encode, Java-derived slug (Arabic names fall back to a random suffix), slug-collision retry once, `DuplicateKeyException` → `SignupConflictException`, wrapped in `TenantContext.enterAuthMode()`/`exitAuthMode()` try/finally.
- [x] **ui:** `SignupView` (`@Route("signup")`, `@AnonymousAllowed`, RTL, Binder validation, inline server conflict messages) + `LoginView` `RouterLink` → `/signup`. Success writes the `signup`/`clinic` `activity_log` row under the new tenant, then redirects to `/login?signup=success`. Note: the spec's `AuthenticationContext.login(...)` does not exist in Vaadin 25.2.6 — the specified fallback (redirect + success banner) is what landed.
- [x] **Docs (same commit):** UC-001 actor + precondition + A4 + BR-004/BR-005; `business_rules.md` BR-G31; `roadmap.md` (this file); `CLAUDE.md` (Phase 1 in progress, V13 listed); `legacy-gaps.md` (email verification + signup throttling deferrals).
- [x] **Tests:** `SignupServiceIT` (provisions trial/active/owner; username, email, slug conflicts; direct `insert into clinic` as `app_rw` without tenant fails), `SignupThenLoginIT` (sign-up → credential lookup), `SignupViewTest` (Karibu: required, mismatch, conflict inline, success navigation), Playwright sign-up → login path in `UC001LogInAndAccessTheSystemIT`. `ModularityTests` stays green.
- [x] **V14 — usernames unique per clinic:** `app_user.clinic_id` added, backfilled from each user's oldest membership (membership-less users deleted), `NOT NULL`; `app_user_username_key` → `app_user_clinic_username_key (clinic_id, username)`, email partial unique index recreated on `(clinic_id, email)`; V11's `app_user_credentials_lookup_by_username` and dead V9 email-keyed lookup dropped in favour of `app_user_credentials_lookup_by_clinic_username(clinic_slug, username)` (same `SECURITY DEFINER` hardening, returns `(id, clinic_id, password_hash, status)`); `signup_clinic_with_owner` now sets `clinic_id` and returns `slug`; trigger guards cross-clinic memberships. Java: `ClinicOSUserDetailsService` deleted, replaced by `ClinicScopedAuthenticationProvider` + `ClinicWebAuthenticationDetails`/`ClinicAuthenticationDetailsSource` (form-login `clinic` field); `AuthenticatedUser` carries `clinicId`; `SignupResult` carries `clinicSlug`; `LoginView` becomes a `LoginOverlay` with a clinic-code field (`login?clinic=` prefills; sign-up links through with `&clinic=<slug>`). Tested: `ClinicScopedAuthenticationProviderIT` (same username in two clinics authenticates to the right tenant; wrong/unknown clinic code, inactive, no-membership, wrong password all generic), `SignupServiceIT` (same username across two clinics succeeds), `schema_checks.sql` sections 9/12 retargeted, `seed_two_clinics.sql` shares one username across both clinics. Follow-up: section 10's blanket "clinic_id column ⇒ RLS enabled" check false-positived on `app_user` once V14 gave it a `clinic_id` column — fixed by excluding `app_user` by name (it's the deliberate platform-table exception, reached only via the pre-auth functions, not RLS). Added section 13, asserting the two invariants of the per-clinic account model directly: (a) the same owner email may be reused across different clinics (exercised via `signup_clinic_with_owner`, since DML on `app_user` is revoked for `app_rw`); (b) `trg_membership_clinic_matches_user` rejects a membership for a clinic that doesn't match its user's own `app_user.clinic_id`, so one account can never span two clinics.
- [x] **Refactor:** all DB access (main + test) moved from `JdbcTemplate` to the jOOQ `DSLContext` over the generated `com.clinicos.shared.jooq` metamodel; codegen moved out of the `codegen` profile into the default build (Docker required for every build), `citext` forced to `String`. `JdbcPermissionsService` → `DefaultPermissionsService`, `JdbcSignupService` → `DefaultSignupService`, `JdbcMembershipLookupService` → `DefaultMembershipLookupService`; ITs renamed to `PermissionsServiceIT` / `SignupServiceIT`; `TestFixtures` and IT assertion queries converted. `TenantConnectionListener`, its unit test, and `PostgresTestSupport` intentionally stay on raw JDBC.

### UI migration — Vaadin Flow → Thymeleaf + HTMX (branch `refactor/thymeleaf-htmx`)

Applied after Phase 1 shipped, keeping the UC-001 endpoint contracts and all prior schema/module decisions intact. **Vaadin views that shipped Phase 1 were replaced, not adapted:** the Vaadin `@Route` views (`HelloView`, `LoginView`, `SignupView`, `MainLayout`, `SectionPlaceholderView`, `TenantSessionBinder`) and Vaadin's `src/main/frontend` theme are deleted; the server-rendered UI lives in `com.clinicos.ui` (MVC `@Controller`s + Thymeleaf) and talks to the browser via HTMX (`hx-*`) + Alpine.js, styled by a Tailwind CLI build (`src/main/styles/` → `target/classes/static/css/app.css`, wired into `process-resources` by maven-antrun). Vaadin dependencies (`vaadin-bom`, flow-server, feature flags) removed from `apps/api/pom.xml`.

- [x] **Auth flow:** `AuthController` renders `/login` (clinic code + username + password, RTL) and `/signup` (self-service, server-side validation, inline conflict messages; success banner back on `/login`). Same Spring Security form login, now `th:action` + CSRF hidden input against the same `ClinicScopedAuthenticationProvider`. Selectors preserved for the Playwright suite: `.clinicos-nav-item`, `.clinicos-logout`, labels + messages verbatim from the UC/legacy screens.
- [x] **Session priming moved serverside:** `TenantSessionBinder` (Vaadin `ServiceInitListener`) became `TenantSessionFilter` (`OncePerRequestFilter`). It runs **inside the Spring Security chain** after `SecurityContextHolderFilter`, not as an auto-discovered servlet filter — a plain `@Component` filter would run before Spring Security and see an empty context on every request, silently skipping priming. Its `FilterRegistrationBean` (enabled=false) suppresses servlet auto-registration. Fix found during the Playwright landing fix: the one-shot `primingAttempted` marker must be set **after** confirming an authenticated principal — setting it first burns the guarantee on the unauthenticated `/login` request and the post-login redirect never primes.
- [x] **Modularity:** the filter moved `ui` → `identity.internal` (a `ui` filter referenced from `ClinicOSSecurityConfig` created an `identity → ui → identity` cycle that failed `ModularityTests`). Session attribute keys now live on `identity.api.SessionKeys` so `ui` reads them across the named-interface boundary.
- [x] **Templates:** `templates/login.html`, `signup.html`, `section.html`, `welcome/home.html`, `welcome/empty.html` + `fragments/{head,drawer,icon}.html`; CSRF forwarded to HTMX via `th:attr="hx-headers=..."` (Thymeleaf `[[...]]` inline does not resolve inside a plain HTML attribute).
- [x] **Tests:** Vaadin view tests (`HelloViewTest`, `LoginViewTest`, `MainLayoutTest`, `SectionPlaceholderViewTest`, `SignupViewTest`, `TenantSessionBinderTest`) replaced by `TenantSessionFilterTest` (5, in `identity.internal`), `AuthControllerTest` (6), `SectionFocusTest` (4), `NavSectionResolverTest` (9); `UC001LogInAndAccessTheSystemIT` (all 6 nested suites, Playwright) now targets the Thymeleaf DOM. `mvn verify` green: 32 unit + 30 IT (DB + browser).

### Phase 2 — UC-002 Manage employees and roles

BR-G04, BR-G05 (unlock), BR-G06, BR-G07.

Admin subtabs `الإعدادات` (employees, evaluation weights summing to 100, working days, attendance rules) and `المستخدمون` (accounts, password change, activate/suspend, link account↔employee). Tables: `employee`, `clinic_settings`, `evaluation_weight`, `incentive_tier`, `membership`, `membership_permission`, `performance_override`.

- [x] Admin subtabs `الإعدادات` and `المستخدمون` with all CRUD operations
- [x] Owner-only `الصلاحيات` matrix — main-app permission keys mapped onto `permission` rows; 19 inventory area keys arrive with Phase 7 but grid is built here

**Slice 2f — Users tab cleanup + duplicate-user 500 fix.** `createClinicUser`'s `DuplicateKeyException` used to abort the inbound `@Transactional` request transaction, so the follow-up `renderCard` reads hit `25P02`/`UnexpectedRollbackException` → 500 instead of a field error; a blank email posted as `''` (not null) also collided on `idx_app_user_email_when_not_null`. Fixes: `UserAdminService.UserValidationException` (Arabic messages keyed by field) thrown from a `count` pre-check in `DefaultUserAdminService.create` before any SQL error, with a constraint-name-mapped `DuplicateKeyException` catch as a race backstop; `UserAdminController.createUser` dropped `@Transactional`, runs create-user → create-employee → link-employee inside a `TransactionTemplate` with the exceptions caught outside, and normalises blank email to `null`. Every created user now always gets an employee record (`ASSISTANT`, defaulted pay/incentive/hire date) and is linked, so the Users-tab employee select and "الحساب ده موظف" checkbox are gone; the row shows role as a read-only chip. Role editing moved to the Settings employee row (`AdminController` builds `employeeRoles`, `EmployeeForm.roleCode`, calls `assignRole` on save when the role changed and isn't `owner`). Tests: `UserAdminControllerTest` (always-create-employee, validation field errors), `AdminControllerTest` (role assign, owner guard), `DefaultUserAdminServiceIT` (dup username/blank-email, connection usable after), `UC002ManageEmployeesAndRolesIT` (duplicate-username Arabic error + two blank-email users succeed).

### Phase 3 — UC-003 Record daily work and attendance

BR-G08, BR-G09, BR-G10, BR-G11.

`تسجيل الموظف`: check-in/check-out (`الحضور` / `الانصراف`, statuses `في الميعاد` / `متأخّر` / `بعد الدوام` / `انصراف مبكّر`), grace-period logic, tasks grouped by dimension (فني / سلوكي / مبادرة) with frequency pills, photo-proof upload to object storage, tasks locked until check-in. Tables: `self_check`, `daily_record`, `daily_task_completion`, `task_definition`, `attachment`.

- [ ] Check-in/check-out with status logic and grace periods
- [ ] Tasks grouped by dimension with frequency pills
- [ ] Photo-proof upload to object storage
- [ ] Tasks locked until check-in

Gamification hero (`#eHero` — level ring, streaks, badges, weekly goal) is backlog, not this phase.

### Phase 4 — UC-004 + UC-005 Evaluate, verify, and view my evaluation

BR-G05, BR-G12, BR-G13, BR-G14, BR-G15, BR-G16, BR-G17, and the admin-dashboard scoring half of BR-G29.

The hardest phase — the scoring engine lives here.

- [x] `تقييم وتحقّق المدير`: date strip, employee chooser, task approve/reject with reason, assigned tasks (`task_assignment`) approve/reject, auto-save. (Per-day manual technical/behavioural 1–5 rating deliberately not built — legacy ratings feed only the trend sparkline, never the score; see UC-004 doc.)
- [x] Scoring service: weighted blend of six categories from `evaluation_weight`; no-data category excluded, not zeroed; coverage reported (BR-G15); manual override is floor (BR-G06); on-time > late > undone for assignments (BR-G13).
- [x] Freeze/unlock: past months read `evaluation_snapshot`; service surfaces V10 trigger errors as clean user errors, not 500s.
- [x] `تقييمي` (UC-005): read-only, own record only (BR-G16); shows the live current month too (deviation from legacy, see UC-005 doc and roadmap note below); owner has no evaluation (BR-G17).
- [x] V-migration: add `review_status` + `review_reason` + `reviewed_by` / `reviewed_at` to `daily_task_completion` (V22).
- [x] BR-by-BR test class before any UI work (`ScoringEngineTest`).

### Phase 5 — UC-006 Prepare and run procedure checklists

BR-G18, BR-G19, BR-G20. `prep_checklist` → `prep_section` → `prep_item`, `prep_run` → `prep_run_item`. Approval gate before a checklist is usable; at least one section with one item; run progress tracked per day. Built-in template library (`📚 قوالب جاهزة`) implemented — manager can import a starter template then edit/approve.

- [x] Checklist CRUD with approval gate (create/edit/approve/unapprove/archive)
- [x] Sections and items management (Alpine-driven dynamic add/remove in the editor)
- [x] Run tracking per day (per-employee/day upsert, resumable same-day, isolated next-day)

### Phase 6 — UC-007 Complete onboarding academy training

BR-G21, BR-G22, BR-G23, BR-G24. `academy_unit`, `academy_question`, `academy_step_submission`, `academy_exam_attempt`. Curriculum = shared core + role units; practical steps need verifier-confirmed photos; exam pool drawn only from covered units; certificate derived from a passing attempt, printed via `window.print()` and the legacy `@media print` rules.

- [x] Curriculum units with teaching content (V-migration needed)
- [x] Practical steps with photo verification
- [x] Exam pool from covered units only
- [x] Certificate generation and print

Legacy extras — quiz kinds (`فهم` / `قرار` / `اكتشف الغلط`), trainer daily rating, the `تقرير القرار` decision report — go to backlog.

### Phase 7 — UC-008 Inventory and procedure costs

BR-G25, BR-G26, BR-G27, BR-G28. The largest phase; the legacy React module is ~4,900 lines across 19 views. Split it:

- [x] **7a Foundation** — `supplier`, `inventory_item`, `stock_movement` append-only ledger; on-hand is `sum(qty_delta)` per `(item_id, location)`, where `location` is the `location_kind` enum (`store` / `tray`), not a table. Views `جرد الأصناف`, `التراي`, `المخزن` (issue), `السجل`. `stock_movement` is revoked for UPDATE/DELETE by a V10 trigger — corrections are compensating rows, and the service layer must be written that way from the start.
- [x] **7b Purchasing** — `purchase_order` + lines, `النواقص والطلب`, `الاستلام` (with mandatory invoice photo), `المرتجعات` with the V10 return-ceiling trigger surfaced as a user error, `الموردين`.
- [x] **7c Procedures and costing** — `procedure`, `procedure_bom`, `procedure_case`, `procedure_case_item` with unit cost frozen at issue time (BR-G28); `قوائم الإجراءات`, `سجل إجراءاتي`.
- [x] **7d Approvals and analytics** — `inventory_change_request` queue (BR-G26), `الاعتمادات`, `الربحية`, `تحليل الأصناف`, `الهدر`, `الأطباء`, `تحليل الموردين`. Note the schema's queue is narrower than the legacy screen: `change_request_kind` is `edit | delete` against an `item_id`, while `الاعتمادات` groups six categories (new procedure-case records, case edits, new procedures, procedure edits, deletions, the item queue). BR-G26 names only stock-affecting item edits/deletes and supplier returns, so the schema satisfies the rule — widening the queue to the other four is a backlog item, decided when this sub-phase starts.

Role scoping (BR-G25) rides on the permission model built in Phase 2.

### Phase 8 — UC-009 Admin dashboard and analytics

BR-G29, BR-G30. `نظرة عامة` (monthly invoice total against a pace-adjusted target — scaled to elapsed working days, not the full month), employee cards with score ring and tier, team summary; `ملف الموظف` with academy qualification progress and status; `سجل النشاط` from `activity_log`. Manager/owner only. Trend sparkline and print-to-PDF employee report remain backlog items.

- [x] Dashboard overview with pace-adjusted targets
- [x] Employee cards with score ring and tier
- [x] Employee file with academy qualification progress
- [x] Activity log view

### Phase 9 — Hardening and release

OpenAPI surface reviewed via springdoc, Playwright smokes green, `schema_checks.sql` wired into `mvn verify`, RLS negative tests (a request without the GUC must fail loudly, not return an empty result), rate limiting on login, activity logging on every mutating action, deployment.

- [ ] OpenAPI surface reviewed via springdoc
- [ ] Playwright smokes green
- [ ] `schema_checks.sql` wired into `mvn verify`
- [ ] RLS negative tests (GUC unset must fail loudly)
- [ ] Rate limiting on login
- [ ] Activity logging on every mutating action
- [ ] Deployment

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

1. `mvn verify` (from `apps/api/`) — unit, Karibu view, and Testcontainers integration tests.
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
