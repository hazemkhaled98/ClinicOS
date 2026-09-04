# ClinicOS — Development Guide

## Stack

| Layer | Technology | Version |
|-------|------------|---------|
| Runtime | Java | 25 (LTS) |
| Framework | Spring Boot | 4.1.0 |
| UI | Vaadin Flow | 25.2.6 |
| Modularity | Spring Modulith | 2.1.1 |
| SQL | jOOQ | 3.21.7 |
| Migrations | Flyway | 13.5.0 |
| Database | PostgreSQL | 17 (prod), Testcontainers in tests |
| Object Storage | MinIO (S3-compatible) | latest |
| Testing | Testcontainers, Karibu, Playwright | 1.21.4 / 2.7.2 / latest |
| API Docs | springdoc OpenAPI | 3.1.0 |

## Module Layout (Spring Modulith)

```
com.clinicos
├── shared         (OPEN) — tenant context, jOOQ metamodel
├── identity       (CLOSED) — clinics, users, memberships, roles, permissions, login, clinic picker (UC-001)
├── clinicconfig   (CLOSED) — clinic settings, evaluation weights, incentive tiers (UC-002 settings half)
├── staff          (CLOSED) — employees, tasks, attendance, daily records (UC-003, UC-002 roster half)
├── evaluation     (CLOSED) — scoring engine, overrides, frozen snapshots (UC-004, UC-005)
├── academy        (CLOSED) — curriculum, exams, certificates (UC-007)
├── prep           (CLOSED) — prep checklists, runs (UC-006)
├── inventory      (CLOSED) — stock, suppliers, POs, returns, approvals (UC-008)
├── procedures     (CLOSED) — procedures, BOM, case costing (UC-008 costing, UC-009)
└── ui             (CLOSED) — Vaadin views, allowedDependencies = all api modules above
```

Each business module exposes `api` and hides `internal`. The `ui` module is verified by `ModularityTests` to only reach published APIs.

## Tenant Context — Critical Rule

**Every transaction must have `app.clinic_id` set before any business query runs.**

- `TenantContext` (ThreadLocal) holds the current `clinic_id` (UUID)
- `TenantConnectionListener` implements `TransactionExecutionListener.afterBegin`
- It runs `SET LOCAL app.clinic_id = '<uuid>'` as the **first** statement in the transaction
- If no tenant is bound, it **throws `IllegalStateException`** — silent empty results from RLS are the dangerous failure mode, never allow them
- This is wired via `TenantConfig.transactionManager()` bean

**Never bypass this.** Any code that runs DB work outside a Spring-managed transaction (e.g., `@Transactional` on a service method) must ensure `TenantContext` is set first.

## Database & Migrations

- Migrations: `apps/api/src/main/resources/db/migration/V1__...V10__.sql`
- V1–V8: schema, tables, triggers
- V9: RLS policies + `app_rw` role (created **without password**)
- V10: cross-cutting triggers (frozen snapshot, return ceiling, append-only ledger, cross-tenant FK guard)
- V11 (planned): `app_user.username citext unique`, privileged auth role, username-keyed credentials lookup

**Local dev**: `docker compose up -d postgres minio`
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
# Generate jOOQ sources (requires Docker for Testcontainers Postgres)
mvn -pl apps/api generate-sources -Pcodegen

# Compile + run unit tests (no Docker needed)
mvn -pl apps/api test

# Run only integration tests (Testcontainers spins up Postgres)
mvn -pl apps/api verify -Dtest=*IT -DfailIfNoTests=false

# Full build with codegen (requires Docker)
mvn -pl apps/api verify -Pcodegen

# Production build (minified frontend)
mvn -pl apps/api -Pproduction package

# Check module boundaries
mvn -pl apps/api test -Dtest=ModularityTests
```

**jOOQ codegen** runs in `generate-sources` phase via `testcontainers-jooq-codegen-maven-plugin` — spins up throwaway Postgres, runs Flyway, generates sources to `target/generated-sources/jooq`. Not committed.

## Run Application Locally

```bash
docker compose up -d postgres minio
# Wait for postgres healthy, then:
docker exec clinicos-postgres psql -U postgres -d clinicos -c "ALTER ROLE app_rw PASSWORD 'local-dev-only';"
mvn -pl apps/api spring-boot:run
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
| `apps/api/src/main/resources/application.yml` | Datasource, Flyway, Vaadin, springdoc config |
| `apps/api/pom.xml` | Full dependency + plugin config |

## Session Workflow

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
| 1 — UC-001 Login | in progress |
| 2 — UC-002 Employees/roles | not started |
| 3 — UC-003 Daily work/attendance | not started |
| 4 — UC-004/005 Evaluation | not started |
| 5 — UC-006 Prep checklists | not started |
| 6 — UC-007 Academy | not started |
| 7a–7d — UC-008 Inventory | not started |
| 8 — UC-009 Admin dashboard | not started |
| 9 — Hardening/release | not started |

## Legacy Data

`index_original.html` (9,144 lines, ~1 MB) is the legacy app. Its embedded Neon credentials and live data are **out of scope** — not touched, not rotated, not migrated. Greenfield build only.