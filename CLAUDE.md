# ClinicOS — Development Guide

## Stack

| Layer | Technology | Version |
|-------|------------|---------|
| Runtime | Java | 25 (LTS) |
| Framework | Spring Boot | 4.1.0 |
| UI | Thymeleaf + HTMX + Alpine.js + Tailwind CSS | 3.1+ / 2.0 / 3.x / 4.1 |
| Modularity | Spring Modulith | 2.1.1 |
| SQL | jOOQ | 3.21.7 |
| Migrations | Flyway | 13.5.0 |
| Database | PostgreSQL | 17 (prod), Testcontainers in tests |
| Object Storage | MinIO (S3-compatible) | latest |
| Testing | Testcontainers, MockMvc, Playwright | 1.21.4 / — / latest |
| API Docs | springdoc OpenAPI | 3.1.0 |

## Module Layout (Spring Modulith)

```
com.clinicos
├── shared         (OPEN) — tenant context, jOOQ metamodel
├── identity       (CLOSED) — clinics, users, memberships, roles, permissions, login (UC-001)
├── clinicconfig   (CLOSED) — clinic settings, evaluation weights, incentive tiers (UC-002 settings half)
├── staff          (CLOSED) — employees, tasks, attendance, daily records (UC-003, UC-002 roster half)
├── evaluation     (CLOSED) — scoring engine, overrides, frozen snapshots (UC-004, UC-005)
├── academy        (CLOSED) — curriculum, exams, certificates (UC-007)
├── prep           (CLOSED) — prep checklists, runs (UC-006)
├── inventory      (CLOSED) — stock, suppliers, POs, returns, approvals (UC-008)
├── procedures     (CLOSED) — procedures, BOM, case costing (UC-008 costing, UC-009)
└── ui             (CLOSED) — Thymeleaf templates + controllers, allowedDependencies = all api modules above
```

Each business module exposes `api` and hides `internal`. The `ui` module is verified by `ModularityTests` to only reach published APIs.

## UI & Design

`DESIGN.md` (repo root) is binding for every new or modified UI component from this point forward. It defines the color tokens, typography scale, spacing, and component specs for ClinicOS's server-rendered Thymeleaf UI.

Design assets live in `ClinicOS Design/<NN>_<screen>/` folders (43 screens × 2 breakpoints). Before implementing or changing any view, read the matching folder's `screen.png` (visual target) and `code.html` (reference markup). Shell chrome (drawer, topbar) is in `drawer_view_desktop/` and `drawer_view_mobile/`. If a screen has no folder, build from the UC spec using `DESIGN.md` conventions plus the nearest existing screen as template — state which screen was used. If a screen folder is missing, create it as part of the implementation with the visual reference.

Three rules apply without exception:
- **No new color outside DESIGN.md's token set.** If a new UI need isn't covered by an existing token, extend DESIGN.md first, then use it — never hardcode a one-off hex value in a view or CSS file.
- **RTL logical properties only.** Every CSS rule uses logical properties (`padding-inline-start/end`, `margin-inline`, `border-inline-start`, `inset-inline`) — never `left`/`right`/`padding-left`/etc. Same applies to Tailwind utilities: translate physical directions (`ps-`/`pe-`/`ms-`/`me-` for padding/margin, `border-s`/`border-e` for borders, `start-`/`end-` for insets) into logical equivalents before landing code. Raw hex values (`bg-[#...]`) and physical Tailwind utilities (`border-l`, `pl-2`, `ml-3`, `-translate-x`) from Stitch comps must be converted to the configured brand palette utilities (`bg-teal-900`, etc.) defined via Tailwind `@theme` in `styles.css`.
- **Tailwind CSS enabled.** Utility classes style the Thymeleaf templates directly. Tailwind sources live in `apps/api/src/main/styles/` — `tokens.css` (`@theme` palette from DESIGN.md), `components.css` (classes shared across templates), `main.css` (entry; `@import`s Tailwind + the other two). Compile with `npm run build:css` (Tailwind CLI 4, `apps/api/package.json`) which writes `target/classes/static/css/app.css` (picked up by the Spring build); templates link `/css/app.css`. Run it after any template or `components.css` change.

### New screen checklist

1. Read `ClinicOS Design/<NN>_*/screen.png` for the visual target and `code.html` for layout/information-architecture/Arabic copy. Ignore its `tailwind.config`, fonts, hex values, and physical-direction utilities — those are never carried into a template.
2. Compose the screen from `apps/api/src/main/styles/components.css` classes. Check `/dev/styleguide` first to see what already exists before writing new markup.
3. Need something not covered by an existing component class or token? Extend `DESIGN.md` first, then `tokens.css`/`components.css`, then add it to the styleguide page — never hardcode a one-off value in a template.
4. Run `npm run build:css` (from `apps/api`), then `mvn test -Dtest=TemplateHygieneTest,CssHygieneTest` before considering the screen done.

Templates live in `apps/api/src/main/resources/templates/`; controllers in `com.clinicos.ui` map routes to them and prepopulate a `LayoutModel` (drawer nav from the session's primed permissions/role). Interactive server round-trips use HTMX (`hx-*` attributes) with a `th:attr`-built `hx-headers` carrying the CSRF token; light client state uses Alpine.js.

## Tenant Context — Critical Rule

**Every transaction must have `app.clinic_id` set before any business query runs.**

- `TenantContext` (ThreadLocal) holds the current `clinic_id` (UUID)
- `TenantConnectionListener` implements `TransactionExecutionListener.afterBegin`
- It runs `SET LOCAL app.clinic_id = '<uuid>'` as the **first** statement in the transaction
- If no tenant is bound, it **throws `IllegalStateException`** — silent empty results from RLS are the dangerous failure mode, never allow them
- This is wired via `TenantConfig.transactionManager()` bean

**Never bypass this.** Any code that runs DB work outside a Spring-managed transaction (e.g., `@Transactional` on a service method) must ensure `TenantContext` is set first.

All business queries go through the generated jOOQ metamodel (`DSLContext`); `TenantConnectionListener` deliberately stays on raw JDBC because it must run `SET LOCAL` on the transaction-bound connection before jOOQ issues anything.

## Database & Migrations

- Migrations: `apps/api/src/main/resources/db/migration/V1__...V14__.sql`
- V1–V8: schema, tables, triggers
- V9: RLS policies + `app_rw` role (created **without password**)
- V10: cross-cutting triggers (frozen snapshot, return ceiling, append-only ledger, cross-tenant FK guard)
- V11: `app_user.username citext unique`, username-keyed `SECURITY DEFINER` credentials lookup + `app_user_memberships_lookup` for session priming (membership → permissions + login activity log; replaced the originally-planned privileged auth role)
- V12: seeds `permission` codes and legacy default `role_permission` sets
- V13: `signup_clinic_with_owner` — `SECURITY DEFINER` self-service sign-up (clinic + owner atomically, before a tenant exists; the only door for `app_rw` to create a clinic)
- V14: `app_user.clinic_id` NOT NULL + `unique (clinic_id, username)` — usernames are per-clinic, not global; auth key becomes (clinic_slug, username) via `app_user_credentials_lookup_by_clinic_username`; sign-up returns the clinic slug (the login screen's clinic code)
- V20: `clinic_settings.working_weekdays` (ISO weekday mask, default Sat-Thu) + `clinic_holiday` table (clinic-wide or per-employee, RLS-protected) — the authoritative work-calendar source for UC-003 A3

## Object Storage (MinIO)

- Config: `clinicos.storage.{endpoint,access-key,secret-key,bucket}` in `application.yml`
- `shared/AttachmentService` — `upload(clinicId, uploadedByMembershipId, file)` writes to MinIO then the `attachment` row; `open(clinicId, attachmentId)` streams it back, tenancy-checked against `clinicId` and RLS
- `spring.servlet.multipart.max-file-size`/`max-request-size` raised (8MB/10MB) to fit phone photos

**Local dev**: `docker compose up -d postgres minio`
- Reset dev data: `./seed-dev.ps1` — wipes ALL business data and reseeds one clinic (`test-clinic`) with four users: `owner` / `manager` / `assistant` / `receptionist`. Owner password `12345678`, others `123`. Needs a migrated DB first. Destructive.
- Postgres: `localhost:5432`, db `clinicos`, user `postgres` / `local-dev-only`
- After Flyway migrates (as `postgres`), `app_rw` must get a password:
  ```sql
  ALTER ROLE app_rw PASSWORD 'local-dev-only';
  ```
- Spring connects as `app_rw` / `local-dev-only` (see `application.yml`)
- MinIO: `localhost:9000` (API), `localhost:9001` (console), `minioadmin` / `minioadmin`

**Tests**: `AbstractPostgresIntegrationTest` spins up Testcontainers Postgres, migrates as superuser, sets `app_rw` password, then Spring connects as `app_rw`. No shared dev DB needed.

## Build & Test

```bash
# Compile + run unit tests (jOOQ codegen in generate-sources requires Docker)
mvn test

# Run only integration tests (Testcontainers spins up Postgres; Playwright chromium auto-installs)
mvn verify -Dtest=*IT -DfailIfNoSpecifiedTests=false -DskipITs=false

# Full build
mvn verify

# Check module boundaries
mvn test -Dtest=ModularityTests
```

Tailwind CSS is compiled in the `process-resources` phase (maven-antrun → `npm run build:css`) into `target/classes/static/css/app.css` — no separate frontend build or profile.

**jOOQ codegen** runs unconditionally in the `generate-sources` phase via `testcontainers-jooq-codegen-maven-plugin` — every build spins up a throwaway Postgres, runs Flyway, and generates sources to `target/generated-sources/jooq`. Not committed. A `citext` forced type maps `citext` columns to `String`.

## Run Application Locally

One-shot script (docker up + healthy wait + `app_rw` password + app run):

```bash
./dev-up.ps1
```

Manual equivalent:

```bash
docker compose up -d postgres minio
# Wait for postgres healthy, then:
docker exec clinicos-postgres psql -U postgres -d clinicos -c "ALTER ROLE app_rw PASSWORD 'local-dev-only';"
mvn spring-boot:run
# App at http://localhost:8080
# OpenAPI at http://localhost:8080/api-docs/ui
```

## Key Files

| File | Purpose |
|------|---------|
| `docs/roadmap.md` | **Source of truth for phase status** — read first, update on every slice |
| `docs/business_rules.md` | BR-G01…BR-G30, traced to UCs |
| `docs/use_cases/UC-001…UC-009.md` | Use case specs |
| `docs/backlog/legacy-gaps.md` | Legacy features not in UCs/schema — schema deltas sketched |
| `ClinicOS Design/` | Per-screen UI reference: `code.html` markup + `screen.png` visual target for each of 43 screens |
| `apps/api/src/main/resources/application.yml` | Datasource, Flyway, Thymeleaf, springdoc config |
| `apps/api/pom.xml` | Full dependency + plugin config |
| `apps/api/src/main/java/com/clinicos/shared/TenantConnectionListener.java` | `SET LOCAL app.clinic_id` on transaction begin |

## Session Workflow

0. **Code discovery — serena-first.** This repo has a `.serena/` semantic index. For symbol-level lookups ("where is X defined", "what calls Y", "list methods of Z") use `mcp__serena__*` tools — `get_symbols_overview`, `find_symbol`, `find_declaration`, `find_referencing_symbols`, `search_for_pattern` — before Grep/Glob. They load preconnected (context: claude-code) and cost less than raw grep over generated jOOQ sources. Fall back to Grep/Glob freely for non-indexed files (yaml, markdown, SQL, HTML) or when serena returns nothing.

1. **At the start of every session — new or resumed — run `git fetch origin`** before any work (or first read), so local refs/tracking match the remote. Applies even when resuming a previous session/branch.
2. **Read `docs/roadmap.md` first.** Find the phase marked `in progress`, scan for the first `- [ ]` step — resume there. All `- [x]` steps are done.
3. **One branch per phase.** Each phase gets its own branch cut from an **updated** `main` (fetch + pull/merge `origin/main` first).
4. **Open a PR after the phase is finished**, as specified by the plan (`docs/roadmap.md`), not before. Base the PR on `main`; push the phase branch and open the PR.
5. Keep `main` clean — land phase work only via its PR.

## After Every Slice / Phase

**Mid-phase slices:** commit each slice, but do **not** flip the phase status.

**Before marking any phase `done`:** run the `/coverage-check` skill against the target use case to verify all implementation and test coverage gaps are closed. If the skill reports any missing items, **do not mark the phase complete** — flag the gaps, address them, and re-run `/coverage-check` until clean.

**After a phase's PR is merged:** mark that phase `done` in **both** the `## Phase Status` table in this `CLAUDE.md` and the status table in `docs/roadmap.md` (same commit):
1. Update `docs/roadmap.md` status table — flip the merged phase to `done`
2. Update this `CLAUDE.md` — flip the same phase to `done` in the `## Phase Status` table, and update this file if architecture/operations changed
3. Commit both (with the phase work or a follow-up status commit)

## Phase Status (from docs/roadmap.md)

| Phase | Status |
|-------|--------|
| 0 — Scaffolding | done |
| 1 — UC-001 Login + Phase 1b sign-up | done |
| 1c — Design system reconciliation | in progress |
| 2 — UC-002 Employees/roles | not started |
| 3 — UC-003 Daily work/attendance | in progress |
| 4 — UC-004/005 Evaluation | done |
| 5 — UC-006 Prep checklists | not started |
| 6 — UC-007 Academy | not started |
| 7a–7d — UC-008 Inventory | not started |
| 8 — UC-009 Admin dashboard | not started |
| 9 — Hardening/release | not started |

## Legacy Data

`index_original.html` (9,144 lines, ~1 MB) is the legacy app. Its embedded Neon credentials and live data are **out of scope** — not touched, not rotated, not migrated. Greenfield build only.