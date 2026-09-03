# SaaS Database Redesign — عيادتي / 3yadty

## Context

Today the whole product is one 1 MB `index_original.html` SPA talking to a single Supabase table `app_data (key text, value jsonb)`. Every entity is a JSON blob behind a key prefix (`config`, `daily:`, `evalSnap:`, `inv:state`, …). Consequences that block selling it as SaaS:

- **No tenancy.** One clinic per deployment. No `clinic_id` anywhere.
- **No real auth.** Passwords live in plaintext inside `config.users`; "owner" is a boolean.
- **No integrity.** Referential integrity, uniqueness and range checks exist only as JavaScript in the browser; anyone with the anon key can rewrite `inv:state`.
- **Blob contention.** All of inventory (items, suppliers, orders, returns, approval queue, procedures) is one row — concurrent writes clobber each other.
- **Not queryable.** Cross-clinic analytics, per-tenant export/delete, and reporting are impossible over opaque JSON.

This document is the target relational schema: PostgreSQL DDL as Flyway migrations plus updated data-model docs. The application rewrite (Java + jOOQ + Vaadin/Hilla) is planned separately after this schema is approved.

**Decisions made:**
- Tenancy: shared schema, `clinic_id` on every tenant table, Postgres RLS as second line of defense.
- Stack: Java + jOOQ + Vaadin/Hilla → Flyway migrations, jOOQ codegen, business rules enforced server-side.
- Data: greenfield. Legacy is reference semantics only; no backfill scripts.
- Scope: domain + tenancy + auth. No billing/plans/usage metering this pass.

Source of truth for semantics: `docs/entity_model.md` (legacy blob shapes), `docs/business_rules.md` (BR-G01…BR-G30), `docs/use_cases/UC-001…UC-009`.

---

## Global conventions

- PK `uuid` default `gen_random_uuid()`; `timestamptz` for instants, `date` for calendar days, `numeric(12,2)` for money, `numeric(5,2)` for scores.
- Monthly periods stored as `date` pinned to day 1 (`period_month`), not `char(7)` — real date arithmetic.
- Soft delete = `archived_at timestamptz`, never a boolean. Active accounts = `status` enum.
- Every tenant table: `clinic_id uuid not null references clinic`, and `(clinic_id, …)` leading index on FKs.
- Tenant-scoped uniqueness always includes `clinic_id` (e.g. `unique (clinic_id, lower(name))`).
- Postgres `enum` types for closed, stable sets (staff role, dimension, frequency, movement reason); `text + check` where values will churn.
- Arabic content: `text` columns, database created with `ICU` collation, `citext` for emails.

---

## Schema

### 1. Tenancy & identity (replaces `config.users`, the `owner` flag, `invRole`)

| Table | Purpose / key columns |
|---|---|
| `clinic` | Tenant root: `name`, `slug` unique, `timezone`, `locale`, `currency`, `status`, `created_at`. |
| `app_user` | Platform-level identity: `email citext unique`, `password_hash`, `full_name`, `status`, `last_login_at`. Same person can belong to several clinics. |
| `membership` | Join of user↔clinic: `clinic_id`, `user_id`, `role_id`, `employee_id` (nullable), `status`, `unique (clinic_id, user_id)`. Login is `app_user`; authorization is `membership` (BR-G01, BR-G02). |
| `role` | Platform-defined: `owner`, `manager`, `assistant`, `receptionist`. No `clinic_id`. |
| `permission` | Platform-defined capability codes. |
| `role_permission` | `role_id` × `permission_id`. Owner row set = all permissions (BR-G03). |
| `membership_permission` | Per-member grant/revoke override, replaces per-user section flags and `invRole` (BR-G02, BR-G25, BR-G30). |

Skipped: clinic-defined custom roles — add nullable `role.clinic_id` when a customer asks.

### 2. Clinic configuration (replaces the `config` blob)

- `clinic_settings` — PK `clinic_id`: shift defaults, `late_grace_minutes` (BR-G08), `working_days_per_month`, `volume_target` (BR-G29), `academy_pass_score`.
- `evaluation_weight` — `(clinic_id, category)` weight per category (BR-G14).
- `incentive_tier` — `(clinic_id, name, min_score, incentive_pct)` (BR-G07).

### 3. Staff & daily work (replaces `config.employees`, `daily:`, `selfcheck:`, `assign:`)

- `employee`, `task_definition` (BR-G04, BR-G09, BR-G10, BR-G11).
- `daily_record` + `daily_task_completion`, `unique (employee_id, work_date)`.
- `self_check`, `unique (employee_id, work_date)`.
- `task_assignment` (BR-G12, BR-G13).

### 4. Evaluation (replaces `override:`, `evalSnap:`, `invoice:`)

- `performance_override` — floor per category, applied at compute time (BR-G06).
- `evaluation_snapshot` — immutable once frozen via `BEFORE UPDATE` trigger (BR-G05).
- `evaluation_component` — `included=false` excludes a no-data category from the weighted average (BR-G15).
- `operations_volume` (BR-G29).

### 5. Academy (replaces `config.academy`, `EMPLOYEE.onboard`, `acadimg:`)

- `academy_unit` (BR-G21), `academy_question` (BR-G23), `academy_step_submission` (BR-G22), `academy_exam_attempt` (BR-G24, certificate derived, not stored).

### 6. Prep checklists (replaces `prep:list`, `prepRun:`)

- `prep_checklist` (BR-G18) → `prep_section` → `prep_item` (BR-G19 checked in service layer).
- `prep_run` (BR-G20) → `prep_run_item`.

### 7. Inventory (replaces the single `inv:state` blob)

- `supplier`, `inventory_item`, `stock_location` (multiple store/tray locations, legacy hardcodes two).
- `stock_alert_threshold`.
- `stock_movement` — append-only ledger, source of truth for on-hand quantity.
  `-- ponytail: balance computed by aggregate; add materialized stock_balance if the sum gets slow past ~1e6 movements/clinic`
- `purchase_order` + `purchase_order_line`.
- `supplier_return` + `supplier_return_line` — BR-G27 enforced by trigger.
- `inventory_change_request` — proposed payload awaiting approval (BR-G26).

Rejected: a single polymorphic "approval request" table across task/checklist/inventory approvals. Task and checklist approval are status columns on the entity; only inventory holds an unapplied payload. Merging them trades three real foreign keys for none.

### 8. Procedures & costing

- `procedure`, `procedure_bom`, `procedure_case`, `procedure_case_item` (unit cost frozen at issue time) (BR-G28).

**PHI note:** `procedure_case.patient_ref` is an opaque clinic-side code — no name, no phone, no clinical detail. Keeps a staff-performance product out of health-record regulatory scope.

### 9. Cross-cutting

- `attachment` — photos live in object storage, DB holds the pointer.
- `activity_log`, `notification`.

### 10. Row-Level Security

- App connects as non-superuser role `app_rw`.
- Every tenant table: RLS enabled, uniform policy `using (clinic_id = current_setting('app.clinic_id', true)::uuid)` with matching `with check`.
- jOOQ issues `set local app.clinic_id = ?` as the first statement of every transaction.
- Platform tables (`clinic`, `app_user`, `role`, `permission`, `role_permission`) are not RLS-scoped.

---

## Deliverables

1. `db/src/main/resources/db/migration/V1__tenancy_and_identity.sql` … `V8__cross_cutting.sql`.
2. `V9__rls_policies.sql`.
3. `V10__triggers.sql`.
4. `db/src/test/resources/seed_two_clinics.sql` + `schema_checks.sql`.
5. This spec.
6. `docs/entity_model.md` rewritten against the new schema; `docs/data_dictionary.md` added.

## Verification

1. `flyway migrate` from clean, twice (idempotent, zero errors).
2. `jooq-codegen` succeeds against the migrated database.
3. `seed_two_clinics.sql` — overlapping names across clinics don't collide.
4. `schema_checks.sql` as `app_rw` — RLS isolation, frozen-snapshot immutability, return-quantity ceiling, rating range/uniqueness constraints, stock-movement sum correctness.
5. `EXPLAIN` the three hot reads (monthly evaluation, current stock, dashboard volume) — index use confirmed.

## Out of scope

- Billing, plans, seats, usage metering.
- Legacy data backfill.
- The application rewrite and cutover.
- Clinic-defined custom roles; multi-currency per clinic.
