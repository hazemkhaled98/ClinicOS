# UC-001 Extension — Self-Service Clinic Sign-Up

**Date:** 2026-09-05
**Status:** Approved, not started
**Phase:** 1b (reopens Phase 1)
**Branch:** `phase-1b-signup` (cut from updated `main`)

## Resume state

Nothing implemented yet. A session picking this up starts at "Implementation, step 1" below and ticks the boxes in `docs/roadmap.md` (Phase 1b block, added by step 4) as it goes.

## Context

ClinicOS is a SaaS product, but there is no way for a clinic to come into existence. UC-001 states the staff member "has an active account **created by the owner or manager**", and the roadmap says "Greenfield. No backfill." Together that means the **first** clinic and the **first** owner have no documented or implemented origin — the only way to get a working login today is to hand-insert rows. This was found while manually testing Phase 1.

This adds a public sign-up flow that provisions a new clinic tenant plus its first owner user in one transaction, and reopens Phase 1 to cover it. Staff accounts stay owner-driven (UC-002); no invite flow, no join-by-code.

**Decisions taken:**
- Sign-up creates **clinic + owner** (tenant self-provisioning). Invites/join-codes out of scope.
- **No email verification** — instant activation. No SMTP, no token table. Deferred to backlog.
- Docs: **extend UC-001 in place**, as a `1b` slice of Phase 1.

## Key finding: zero DDL needed

The schema already supports this:
- `clinic.status` defaults to `'trial'` (`V1__tenancy_and_identity.sql:17`)
- `membership.status` defaults to `'active'`, `role` is seeded with `owner`
- `V12__seed_permissions.sql` already grants `owner` everything (BR-G03)

The only blocker is privileges: V9 explicitly `revoke insert, update, delete on clinic, app_user, ...` from `app_rw`, and `membership` is RLS-scoped so it is unreachable before `app.clinic_id` exists. A `SECURITY DEFINER` function (owned by the migration superuser) is therefore the *only* possible door. `V9__rls_policies.sql:8` already anticipates exactly this — "a `SECURITY DEFINER` function that itself enforces cross-tenant rules (e.g. **signup**, membership creation)". So V13 adds **one function, no tables, no columns**.

## Implementation

### 1. `V13__signup.sql`

One `SECURITY DEFINER` function, hardened the same way as the V11 pair (`set search_path = pg_catalog, public, pg_temp`, schema-qualified relations, `revoke all ... from public`, `grant execute ... to app_rw`):

```
signup_clinic_with_owner(
    p_clinic_name text, p_slug text,
    p_full_name text, p_username citext, p_email citext, p_password_hash text
) returns table (user_id uuid, clinic_id uuid, membership_id uuid)
```
All three ids come back so the caller can bind the tenant and write the `activity_log` row without a second lookup.

`volatile` (not `stable` like the V11 lookups). Body: insert `clinic` (status defaults `trial`) → insert `app_user` (status `active`) → insert `membership` (`role.code = 'owner'`, status `active`) → return the ids. Unique violations propagate as SQLSTATE 23505 with the constraint name; Java maps them.

Model it on the existing functions in `apps/api/src/main/resources/db/migration/V11__auth_username.sql:30-58`.

### 2. `identity` module

Follow the existing api/internal split and the `CredentialsLookupService` shape (auth-mode + `TransactionTemplate` + `DSLContext`).

- `identity/api/SignupService.java` — interface: `SignupResult signUp(SignupRequest request)` where `record SignupResult(UUID userId, UUID clinicId, UUID membershipId)`; `record SignupRequest(String clinicName, String fullName, String username, String email, String rawPassword)`; `SignupConflictException(Field field)` with `enum Field { USERNAME, EMAIL, CLINIC_SLUG }`.
- `identity/internal/DefaultSignupService.java` — encodes the password with the existing `PasswordEncoder` bean (`ClinicOSSecurityConfig:36`, Argon2), derives the slug, wraps the call in `TenantContext.enterAuthMode()` / `exitAuthMode()` in try/finally (same as `ClinicOSUserDetailsService:34-53`), calls the V13 function, maps `DuplicateKeyException` → `SignupConflictException` by constraint name.
- Slug derivation stays in Java, not SQL: lowercase, non-alphanumeric → `-`, collapse repeats, trim. Arabic clinic names reduce to empty → fall back to a short random suffix. On a slug collision, retry once with a random suffix before surfacing the conflict.

### 3. `ui` module

- `ui/SignupView.java` — `@Route(value = "signup", autoLayout = false)`, `@AnonymousAllowed`, `dir="rtl"`, styled like `LoginView`/`ClinicPickerView`. Fields: clinic name, full name, username, password, confirm password, email (optional). Client-side `Binder` validation: required fields, password min length, confirm match. Server errors from `SignupConflictException` render inline on the offending field in Arabic.
- On success: write an `activity_log` row (`action = "signup"`, `entity_type = "clinic"`) under the new tenant via the existing `ActivityLogService` — same shape as the login write in `ClinicPickerView:87`, which needs both `clinic_id` and `actor_membership_id` and so can only happen after the function returns.
- Then log the new owner in immediately with Vaadin's `AuthenticationContext.login(new UsernamePasswordAuthenticationToken(username, rawPassword))` — it handles the security-context save and session fixation properly. They have exactly one membership, so `ClinicPickerView` auto-selects it and drops them in the app. If `AuthenticationContext` proves awkward under Vaadin 25's `VaadinSecurityConfigurer`, fall back to redirecting to `/login` with a success banner.
- `ui/LoginView.java` — add a `RouterLink` under the form: `ليس لديك حساب؟ أنشئ عيادة جديدة` → `/signup`.
- **No Spring Security config change**: `VaadinSecurityConfigurer` derives route access from `@AnonymousAllowed`.

### 4. Docs (same commit as the code)

- `docs/use_cases/UC-001-log-in-and-access-the-system.md` — Primary Actor gains "Prospective Clinic Owner"; the "account created by the owner or manager" precondition gains an "or created via sign-up (A4)" clause; new `### A4: New Clinic Sign-Up` alternative flow triggered at step 1 (numbered flow ending "Use case continues at step 5"); new `### BR-004` (sign-up provisions clinic in `trial` + owner membership atomically) and `### BR-005` (username is globally unique across all clinics; sign-up names the conflicting field — this is a pre-auth form, not a credential check, so the generic-error rule of A1 does not apply).
- `docs/business_rules.md` — add `BR-G31` (next free ID), sourced to `UC-001 BR-004`.
- `docs/roadmap.md` — flip Phase 1 to `in progress` in the status table; add a `Phase 1b — self-service sign-up` bullet block under the Phase 1 section with unchecked boxes mirroring the steps above.
- `CLAUDE.md` — Phase 1 → `in progress` in the Phase Status table; add V13 to the migrations list under "Database & Migrations".
- `docs/backlog/legacy-gaps.md` — add rows for the two deliberate deferrals: email verification, and sign-up rate limiting / abuse throttling.
- `docs/entity_model.md` / `docs/data_dictionary.md` — no change (no DDL).

Flip Phase 1 back to `done` only after `/coverage-check` on UC-001 is clean and the PR merges.

## Deliberately skipped

Email verification, CAPTCHA/rate limiting, terms-acceptance record, trial expiry enforcement, invite flow, user-chosen clinic slug. Add verification + throttling before the app is publicly reachable (Phase 9 hardening) — leave a `ponytail:` comment on the service naming that ceiling.

## Tests

- `identity/internal/SignupServiceIT` — happy path asserts one `clinic` (status `trial`), one `app_user` (status `active`, Argon2 hash verifies), one `membership` (role `owner`, status `active`); duplicate username → `SignupConflictException(USERNAME)`; slug collision retries then conflicts; direct `insert into clinic` as `app_rw` without a tenant still fails (proves the function is the only door).
- `identity/internal/SignupThenLoginIT` — sign up, then `ClinicOSUserDetailsService.loadUserByUsername` returns an `AuthenticatedUser` whose hash matches the raw password.
- `ui/SignupViewTest` (Karibu) — required-field validation, password mismatch, conflict message rendering, success navigation.
- Extend `ui/UC001LogInAndAccessTheSystemIT` (Playwright) with the sign-up → auto-login → landing-section path.
- `ModularityTests` already guards `ui` → `identity.api` only; it must stay green.

Use `TestFixtures` / `AbstractPostgresIntegrationTest` as-is — no new test infra.

## Verification

```bash
docker compose up -d postgres minio
docker exec clinicos-postgres psql -U postgres -d clinicos -c "ALTER ROLE app_rw PASSWORD 'local-dev-only';"
mvn verify -Pcodegen
mvn spring-boot:run
```
Then in the browser: `http://localhost:8080/login` → follow the sign-up link → create a clinic → confirm you land in the app shell with the full owner nav (BR-G03), log out, log back in with the same credentials, and confirm the clinic picker is skipped (single membership).

Finish with `/coverage-check UC-001`; do not mark Phase 1 `done` until it reports clean.
