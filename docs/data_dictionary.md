# Data Dictionary

Column-level reference for the schema defined in `db/src/main/resources/db/migration/V1`–`V10`. See `docs/entity_model.md` for relationships and rationale, and `docs/superpowers/specs/2026-09-04-saas-database-design.md` for the design decisions behind it.

Conventions: every table's primary key is `id uuid default gen_random_uuid()` unless noted otherwise. Every tenant table has RLS enabled (`V9__rls_policies.sql`); tables without a direct `clinic_id` column are scoped through the parent named in their "Tenant scope via" row.

## V1 — Tenancy & identity

### clinic
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| name | text | not null |
| slug | text | not null, unique |
| timezone | text | not null, default 'Africa/Cairo' |
| locale | text | not null, default 'ar-EG' |
| currency | text | not null, default 'EGP' |
| status | clinic_status enum (trial, active, suspended, cancelled) | not null, default 'trial' |
| created_at | timestamptz | not null, default now() |

### app_user
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| email | citext | not null, unique |
| password_hash | text | not null |
| full_name | text | not null |
| status | text | not null, in (active, suspended) |
| last_login_at | timestamptz | |
| created_at | timestamptz | not null, default now() |

### role / permission
Platform-defined, no `clinic_id`. `role.code`: owner, manager, assistant, receptionist. `permission.code`: application-defined capability strings (e.g. `employees.manage`).

### role_permission
PK `(role_id, permission_id)`, both FK cascade delete.

### membership
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| clinic_id | uuid | FK clinic, not null |
| user_id | uuid | FK app_user, not null |
| role_id | uuid | FK role, not null |
| employee_id | uuid | FK employee, nullable (wired in V3) |
| status | membership_status enum (invited, active, suspended) | not null, default 'active' |
| created_at | timestamptz | not null, default now() |

Unique `(clinic_id, user_id)`.

### membership_permission
PK `(membership_id, permission_id)`. `granted boolean not null`. Tenant scope via `membership`.

## V2 — Clinic configuration

### clinic_settings
PK `clinic_id` (FK clinic). `default_shift_start/end time`, `late_grace_minutes int >= 0 default 10`, `working_days_per_month int > 0 default 26`, `volume_target numeric(12,2) >= 0`, `academy_pass_score int 0–100 default 70`.

### evaluation_weight
PK `(clinic_id, category)`. `category` is `eval_category` enum (completion, fanni, solooki, ibda3, attendance, volume). `weight numeric(5,2) 0–100`.

### incentive_tier
`id` PK, `clinic_id` FK, `name text`, `min_score numeric(5,2) 0–100`, `incentive_pct numeric(5,2) 0–100`. Unique `(clinic_id, name)`.

## V3 — Staff & daily work

### employee
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| clinic_id | uuid | FK clinic, not null |
| name | text | not null |
| base_pay | numeric(10,2) | not null, >= 0 |
| max_incentive | numeric(10,2) | not null, >= 0 |
| shift_start / shift_end | time | nullable |
| custom_shift | boolean | not null, default false |
| hired_at | date | not null, default current_date |
| archived_at | timestamptz | nullable (soft delete) |

### task_definition
`clinic_id`, `staff_role`, `name`, `dimension` (fanni/solooki/ibda3), `frequency` (daily/weekly/monthly/custom), `every_n int >= 1`, `interval_unit` (day/week/month), `requires_photo bool`, `display_order int`, `archived_at`, `created_at`. Check: `every_n`/`interval_unit` set iff `frequency = 'custom'`.

### daily_record
`clinic_id`, `employee_id` FK, `work_date date`, `fanni/solooki/ibda3 smallint 1–5`, `rated_by` FK membership, `rated_at`. Unique `(employee_id, work_date)`.

### daily_task_completion
PK `(daily_record_id, task_definition_id)`. `done bool`, `completed_at`, `photo_id` FK attachment (wired V8). Tenant scope via `daily_record`.

### self_check
`clinic_id`, `employee_id` FK, `work_date`, `checked_in_at not null`, `checked_out_at`. Unique `(employee_id, work_date)`.

### task_assignment
`clinic_id`, `employee_id` FK, `name`, `proposed_by` (manager/employee), `assigned_at`, `due_date`, `status` (pending/approved/rejected), `approved_by/at`, `done_at`, `proof_photo_id`/`verification_photo_id` FK attachment (wired V8).

## V4 — Evaluation

### performance_override
PK `(employee_id, period_month, category)`. `floor_value numeric(5,2) 0–100`, `set_by` FK membership, `set_at`. `period_month` checked to be the 1st of a month. Tenant scope via `employee`.

### evaluation_snapshot
`clinic_id`, `employee_id` FK, `period_month date`, `final_score numeric(5,2) 0–100`, `incentive_amount numeric(10,2) >= 0`, `weights jsonb not null`, `frozen_at/by`, `unlocked_at/by`. Unique `(employee_id, period_month)`.
**Immutable while `unlocked_at is null`** — enforced by `trg_evaluation_snapshot_frozen` (`V10`); the update that sets `unlocked_at` from null is itself always permitted.

### evaluation_component
PK `(snapshot_id, category)`. `raw_score numeric(5,2) 0–100` (nullable — no data for that category), `weight numeric(5,2) >= 0`, `included bool default true`. Tenant scope via `evaluation_snapshot`.

### operations_volume
PK `(clinic_id, period_month)`. `amount numeric(12,2) >= 0`, `recorded_by`, `recorded_at`.

## V5 — Academy & prep

### academy_unit
`clinic_id`, `applies_to` (core/assistant/receptionist), `title`, `display_order`, `requires_photo`, `archived_at`.

### academy_question
`unit_id` FK cascade, `prompt text`, `options jsonb`, `correct_index int >= 0`. Tenant scope via `academy_unit`.

### academy_step_submission
`clinic_id`, `employee_id` FK, `unit_id` FK, `photo_id` FK attachment (V8), `submitted_at`, `verified_by/at`, `status` (pending/verified/rejected).

### academy_exam_attempt
`clinic_id`, `employee_id` FK, `score int 0–100`, `passed bool`, `attempted_at`.

### prep_checklist
`clinic_id`, `name`, `status` (draft/approved), `approved_by/at`, `archived_at`.

### prep_section
`checklist_id` FK cascade, `title`, `display_order`. Tenant scope via `prep_checklist`.

### prep_item
`section_id` FK cascade, `name`, `display_order`. Tenant scope via `prep_section` → `prep_checklist`.

### prep_run
`clinic_id`, `checklist_id` FK, `employee_id` FK, `run_date`. Unique `(clinic_id, checklist_id, employee_id, run_date)`.

### prep_run_item
PK `(prep_run_id, prep_item_id)`. `checked_at`. Tenant scope via `prep_run`.

## V6 — Inventory

### supplier
`clinic_id`, `name`, `contact`, `archived_at`.

### inventory_item
`clinic_id`, `name`, `uom`, `units_per_pack int >= 1`, `unit_cost numeric(10,2) >= 0`, `preferred_supplier_id` FK supplier, `expiry_date`, `archived_at`.

### stock_location
`clinic_id`, `name`, `kind` (store/tray). Unique `(clinic_id, name)`.

### stock_alert_threshold
PK `(item_id, location_id)`. `threshold int >= 0`. Tenant scope via `inventory_item`.

### stock_movement
Append-only ledger; on-hand = `sum(qty_delta)` per `(item_id, location_id)`.
| Column | Type | Constraints |
|---|---|---|
| id | uuid | PK |
| clinic_id | uuid | FK clinic |
| item_id | uuid | FK inventory_item |
| location_id | uuid | FK stock_location |
| qty_delta | numeric(12,2) | not null (signed) |
| reason | movement_reason enum (receipt, issue, transfer, return, adjustment, count) | not null |
| ref_type | text | nullable — e.g. 'purchase_order', 'procedure_case' |
| ref_id | uuid | nullable |
| created_by | uuid | FK membership |
| created_at | timestamptz | not null, default now() |

`-- ponytail: balance computed by aggregate; add a materialized stock_balance table if the sum gets slow past ~1e6 movements per clinic`

### purchase_order
`clinic_id`, `supplier_id` FK, `status` (draft/placed/received/cancelled), `placed_by/at`, `received_at`, `invoice_photo_id` FK attachment (V8).

### purchase_order_line
`order_id` FK cascade, `item_id` FK, `qty_ordered numeric > 0`, `unit_cost numeric >= 0`, `qty_received numeric >= 0 default 0`. Tenant scope via `purchase_order`.

### supplier_return
`clinic_id`, `purchase_order_id` FK, `supplier_id` FK, `requested_by/at`, `status` (pending/approved/rejected), `approved_by/at`.

### supplier_return_line
`supplier_return_id` FK cascade, `purchase_order_line_id` FK, `qty numeric > 0`. Tenant scope via `supplier_return`.
**Return-quantity ceiling enforced by `trg_supplier_return_line_ceiling`** (`V10`): sum of `qty` across all non-self return lines for a `purchase_order_line`, plus the new/updated row's `qty`, cannot exceed that line's `qty_received`.

### inventory_change_request
`clinic_id`, `kind` (edit/delete), `item_id` FK nullable, `payload jsonb` (proposed new values for edit; null for delete), `requested_by/at`, `status` (pending/approved/rejected), `decided_by/at`.

## V7 — Procedures & costing

### procedure
`clinic_id`, `name`, `price numeric >= 0`, `labor_cost numeric >= 0 default 0`, `doctor_fee numeric >= 0 default 0`, `archived_at`.

### procedure_bom
PK `(procedure_id, item_id)`. `qty numeric > 0`. The standard bill of materials for a procedure type.

### procedure_case
`clinic_id`, `procedure_id` FK, `employee_id` FK, `doctor_name text` nullable, `patient_ref text` nullable — **opaque clinic-side reference code, never a patient name/phone**, `performed_at`.

### procedure_case_item
`procedure_case_id` FK cascade, `item_id` FK, `qty numeric > 0`, `unit_cost_at_time numeric >= 0` (frozen at issue time so historical case cost doesn't drift when `inventory_item.unit_cost` later changes). Tenant scope via `procedure_case`. Each row is backed by a `stock_movement` with `ref_type = 'procedure_case'`.

## V8 — Cross-cutting

### attachment
`clinic_id`, `storage_key text` (object storage pointer, not the file itself), `content_type`, `byte_size int >= 0`, `uploaded_by`, `uploaded_at`.

### activity_log
`clinic_id`, `actor_membership_id` FK nullable, `action text`, `entity_type text`, `entity_id uuid` nullable, `detail jsonb` nullable, `occurred_at`.

### notification
`clinic_id`, `recipient_membership_id` FK cascade, `kind text`, `payload jsonb`, `read_at` nullable, `created_at`.

## Enum reference

| Enum | Values |
|---|---|
| clinic_status | trial, active, suspended, cancelled |
| membership_status | invited, active, suspended |
| eval_category | completion, fanni, solooki, ibda3, attendance, volume |
| staff_role | assistant, receptionist |
| task_dimension | fanni, solooki, ibda3 |
| task_frequency | daily, weekly, monthly, custom |
| interval_unit | day, week, month |
| assignment_proposer | manager, employee |
| assignment_status | pending, approved, rejected |
| academy_audience | core, assistant, receptionist |
| submission_status | pending, verified, rejected |
| checklist_status | draft, approved |
| location_kind | store, tray |
| movement_reason | receipt, issue, transfer, return, adjustment, count |
| po_status | draft, placed, received, cancelled |
| return_status | pending, approved, rejected |
| change_request_kind | edit, delete |
| change_request_status | pending, approved, rejected |
