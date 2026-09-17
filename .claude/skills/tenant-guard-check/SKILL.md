---
name: tenant-guard-check
description: Verify a DB-touching code change runs inside a Spring-managed transaction with app.clinic_id bound via TenantContext before any query executes. Use before finishing any change that adds or edits jOOQ/DSLContext calls, repository/service methods, or anything touching TenantConnectionListener.
---

# Tenant Guard Check

ClinicOS is multi-tenant via PostgreSQL RLS. `TenantConnectionListener` runs `SET LOCAL app.clinic_id = '<uuid>'` as the first statement of every transaction. If a code path runs DB work without that binding, RLS returns **silent empty results** — the worst failure mode, not an error.

## When invoked

1. Identify every new/changed method that issues a DB query (DSLContext, generated jOOQ metamodel calls, or any `Connection`/`Statement` usage).
2. For each one, trace the call path back to confirm it runs inside a `@Transactional` boundary managed by `TenantConfig.transactionManager()` — not a bare `DataSource.getConnection()`, not a background thread/executor without `TenantContext` propagation, not a test that bypasses `AbstractPostgresIntegrationTest`.
3. Confirm `TenantContext` is populated before the transaction begins on that call path (e.g. via the request filter/session, not assumed).
4. Flag any raw JDBC outside `TenantConnectionListener.java` — that file is the sole intentional exception per CLAUDE.md.
5. Flag any `@Async`, `CompletableFuture`, scheduled task, or new thread that touches the DB — `TenantContext` is a ThreadLocal and does not cross threads automatically.

## Output

Report per finding: file:line, the call path, and whether the tenant binding is confirmed, missing, or unclear. Do not guess — read the actual call chain. If unclear, say so and point to what needs manual verification.
