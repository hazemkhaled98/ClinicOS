# ClinicOS — Dev Setup Reference

Reference material moved out of `CLAUDE.md` to keep the per-request context small. See `CLAUDE.md` for
behavioural rules (tenant context, UI rules, session workflow).

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

Each business module exposes `api` and hides `internal`. The `ui` module is verified by `ModularityTests` to
only reach published APIs.

## Database & Migrations

- Migrations: `apps/api/src/main/resources/db/migration/V1__...V14__.sql`
- V1–V8: schema, tables, triggers
- V9: RLS policies + `app_rw` role (created **without password**)
- V10: cross-cutting triggers (frozen snapshot, return ceiling, append-only ledger, cross-tenant FK guard)
- V11: `app_user.username citext unique`, username-keyed `SECURITY DEFINER` credentials lookup +
  `app_user_memberships_lookup` for session priming (membership → permissions + login activity log;
  replaced the originally-planned privileged auth role)
- V12: seeds `permission` codes and legacy default `role_permission` sets
- V13: `signup_clinic_with_owner` — `SECURITY DEFINER` self-service sign-up (clinic + owner atomically,
  before a tenant exists; the only door for `app_rw` to create a clinic)
- V14: `app_user.clinic_id` NOT NULL + `unique (clinic_id, username)` — usernames are per-clinic, not global;
  auth key becomes (clinic_slug, username) via `app_user_credentials_lookup_by_clinic_username`; sign-up
  returns the clinic slug (the login screen's clinic code)

**Local dev**: `docker compose up -d postgres minio`
- Postgres: `localhost:5432`, db `clinicos`, user `postgres` / `local-dev-only`
- After Flyway migrates (as `postgres`), `app_rw` must get a password:
  ```sql
  ALTER ROLE app_rw PASSWORD 'local-dev-only';
  ```
- Spring connects as `app_rw` / `local-dev-only` (see `application.yml`)
- MinIO: `localhost:9000` (API), `localhost:9001` (console), `minioadmin` / `minioadmin`

**Tests**: `AbstractPostgresIntegrationTest` spins up Testcontainers Postgres, migrates as superuser, sets
`app_rw` password, then Spring connects as `app_rw`. No shared dev DB needed.

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

Tailwind CSS is compiled in the `process-resources` phase (maven-antrun → `npm run build:css`) into
`target/classes/static/css/app.css` — no separate frontend build or profile.

**jOOQ codegen** runs unconditionally in the `generate-sources` phase via
`testcontainers-jooq-codegen-maven-plugin` — every build spins up a throwaway Postgres, runs Flyway, and
generates sources to `target/generated-sources/jooq`. Not committed. A `citext` forced type maps `citext`
columns to `String`.

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

## Legacy Data

`index_original.html` (9,144 lines, ~1 MB) is the legacy app. Its embedded Neon credentials and live data are
**out of scope** — not touched, not rotated, not migrated. Greenfield build only.
