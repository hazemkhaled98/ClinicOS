# Implement UC-006 preparation checklists

## Goal

Deliver the complete UC-006 workflow: clinic staff create or import ordered procedure checklists, managers approve them, and staff persist and resume per-day preparation progress.

## Scope

- List active clinic checklists by approval state.
- Create, edit, order, and archive checklist sections and items.
- Require at least one non-empty section containing at least one non-empty item.
- Return every new or edited checklist to `draft` and clear its prior approval.
- Allow only owners and managers to approve or withdraw approval.
- Allow live runs only for approved checklists.
- Persist one run per checklist, employee, and calendar day; support item toggles and reset.
- Provide the three ready-made templates represented by design screen 07 and copy an imported template into a clinic-owned draft checklist.
- Implement desktop and mobile layouts from design screens 06 through 10 using existing ClinicOS components and tokens.

## Non-goals

- Template creation or editing through the application.
- Versioned historical snapshots of checklist definitions.
- Essential-item classification, because it is absent from UC-006 and the current schema and remains in the legacy-gap backlog.
- Associating a run with an appointment or patient.
- Offline progress updates.

## Architecture

The `prep` module owns checklist definitions, template imports, approvals, and runs. It exposes one API service consumed by the `ui` module and keeps all jOOQ access in `prep.internal`. Each public operation receives the current clinic and actor identifiers; every database operation runs inside a Spring-managed transaction after `TenantContext` has been bound.

The UI uses server-rendered Thymeleaf pages and narrow HTMX mutations. Alpine is limited to editor row manipulation before form submission; all validation, authorization, status transitions, and persistence remain server-side.

## Data model

Migration `V23__prep_templates.sql` adds an immutable global catalog:

- `prep_template`: stable code, Arabic display name, and display order.
- `prep_template_section`: template reference, Arabic title, and display order.
- `prep_template_item`: section reference, Arabic item name, and display order.

Flyway seeds the three templates shown in design screen 07. These tables have no `clinic_id` because every clinic reads the same catalog. `app_rw` receives `SELECT` only; application DML is not granted. Import reads the catalog and inserts new rows into the existing tenant-scoped `prep_checklist`, `prep_section`, and `prep_item` tables in one transaction.

The existing preparation tables remain authoritative:

- `prep_checklist.status` uses `draft` and `approved`.
- `approved_by` and `approved_at` record the latest approval.
- `archived_at` implements deletion without destroying prior runs.
- `prep_run` keeps the existing unique key `(clinic_id, checklist_id, employee_id, run_date)`.
- `prep_run_item` stores the checked state for each item in that daily run.

No snapshot tables are added. UC-006 requires resumable daily progress, not an immutable audit history of past checklist contents.

## Authorization

Any authenticated active clinic member may view the preparation area. Active members linked to an employee may create, import, edit, archive, and run checklists. Owner and manager roles may additionally approve or withdraw approval.

Authorization is enforced inside the preparation service as well as in controller visibility. A crafted request cannot approve as assistant or receptionist, operate across clinics, run a draft checklist, or mutate an archived checklist.

## Service contract

`PrepChecklistService` exposes records and operations for:

- listing active checklists and loading one checklist with ordered sections and items;
- listing and importing ordered templates;
- saving a complete checklist definition atomically;
- archiving, approving, and withdrawing approval;
- loading today's run, toggling one run item, and resetting today's run.

Saving uses the submitted ordered definition as the complete desired state. The service validates names and structure, updates the checklist, replaces its child rows in one transaction, sets status to `draft`, and clears approval fields. Import builds its request from seeded templates and uses the same persistence path.

Approval succeeds only when the checklist is active and still satisfies BR-G19. Run loading and mutation succeed only while the checklist is active and approved. The service resolves or receives the actor's linked employee and never trusts an employee identifier posted by the browser.

## UI flow

Routes follow the existing server-rendered controller pattern:

- `GET /prep`: screen 06 empty state or screen 08 pending/approved cards.
- `GET /prep/templates`: screen 07 template catalog.
- `POST /prep/templates/{code}/import`: create a draft copy and redirect to its editor.
- `GET /prep/checklists/new` and `GET /prep/checklists/{id}/edit`: screen 09 editor.
- `POST /prep/checklists` and `POST /prep/checklists/{id}`: validate and save the complete definition.
- `POST /prep/checklists/{id}/approve`, `/unapprove`, and `/archive`: state transitions followed by refreshed cards or redirects.
- `GET /prep/checklists/{id}/run`: screen 10 daily run.
- `POST /prep/checklists/{id}/run/items/{itemId}` and `/reset`: HTMX progress mutations.

Forms and HTMX requests use the shared CSRF header in the head fragment. Validation failures render Arabic field or form errors without losing the submitted editor rows. Archive requires confirmation. The run page displays checked count and total count; essential badges and essential-only warnings are omitted.

## Business-rule behavior

- BR-G18: create, edit, import, and unapprove produce `draft`; only owner/manager approval permits a run.
- BR-G19: save and approval both reject definitions without at least one section containing at least one non-blank item.
- BR-G20: the daily unique key isolates progress by clinic, checklist, employee, and date; revisiting the same day resumes it, while the next date starts a separate run.

Deleting a checklist uses `archived_at`. Archived definitions disappear from normal lists and reject future edits, approvals, and runs, while retained rows preserve existing run references.

## Failure handling

Missing, unauthorized, and invalid run mutations return HTTP 422 from the controller. Domain validation returns Arabic form errors. Concurrent creation of today's run handles the unique-key race by re-reading the winning row. Every multi-row save or import is atomic, so a failed child insert cannot leave a partial checklist.

## Verification

- Unit tests cover controller routing, Arabic validation rendering, role-dependent controls, and HTMX fragments.
- Integration tests cover CRUD, ordered children, approval invalidation, approval role checks, archive behavior, tenant isolation, template read-only access and deep-copy import, daily resume, cross-day isolation, item toggle, reset, and draft-run rejection.
- `UC006PrepChecklistsIT` exercises list, editor, approval, archive, and daily-run browser flows.
- UI completion requires `npm run build:css`, `TemplateHygieneTest`, `CssHygieneTest`, `ModularityTests`, `mvn verify`, a clean `/coverage-check` for UC-006, and a clean `/manual-testing` pass.

## Delivery boundaries

Phase 5 should be implemented only after the currently active roadmap phase is resolved and the phase branch is cut from updated `main`. The roadmap status changes to `in progress` with the first Phase 5 commit. Mid-phase slices update their checkboxes and commit independently; the phase is marked done only after coverage and manual testing are clean and its PR is merged.
