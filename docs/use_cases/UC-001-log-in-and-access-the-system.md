# Use Case: Log In and Access the System

## Overview

**Use Case ID:** UC-001
**Use Case Name:** Log In and Access the System
**Primary Actor:** Any Staff Member (Owner/Manager, Assistant, Receptionist); Prospective Clinic Owner (A4)
**Goal:** A staff member authenticates with their credentials and reaches the part of the system their role is permitted to use.
**Status:** Tested

## Preconditions

- The staff member has an active account created by the owner or manager — or created via sign-up (A4).
- ~~The clinic's shared data has finished loading from the cloud (or the device falls back to offline queuing).~~ Not applicable — see A2.

## Main Success Scenario

1. The staff member opens the application and is shown the login screen.
2. The staff member enters their clinic code (the clinic's slug, shown after sign-up), username, and password.
3. The system verifies the credentials against the active accounts for that clinic.
4. If the staff member holds active memberships at more than one clinic, the system shows a clinic picker and the staff member selects which clinic to work in for this session. Under the current per-clinic-account schema (BR-005) an account belongs to exactly one clinic, so this step always auto-selects that single clinic today — the picker's multi-clinic branch is kept for a future use case that lets one person hold memberships across clinics.
5. The system starts a session for the staff member and remembers it on the device.
6. The system determines which sections of the application the staff member's role is allowed to see.
7. The system opens the first section the staff member is permitted to use and shows the navigation menu for their role.
8. The staff member logs out when finished, ending the session.

## Alternative Flows

### A1: Invalid Credentials

**Trigger:** The clinic code is unknown, the username is not found in that clinic, the account is inactive, the account has no active membership, or the password does not match (step 3)
**Flow:**

1. The system shows a single generic error message and keeps the staff member on the login screen.
2. Use case ends.

### A2: No Confirmed Connection to the Shared Data — Not Applicable

**Status:** Out of scope by decision (see `docs/roadmap.md`, "Offline mode" row and the UC-001 deviation note). ClinicOS is a server-rendered Vaadin app with no client-side offline queue — this flow does not exist and will not be implemented.

### A3: Returning to a Previously Open Section

**Trigger:** The staff member has logged in before on this device (step 7)
**Flow:**

1. The system reopens the section the staff member last used, if their role still permits it.
2. Use case continues at step 8.

### A4: New Clinic Sign-Up

**Actor:** Prospective Clinic Owner

**Trigger:** No account exists yet; the clinic itself does not exist as a tenant (step 1)
**Flow:**

1. The prospective owner opens the sign-up form (`/signup`) from the login screen.
2. The prospective owner enters clinic name, full name, username, password, and (optionally) email.
3. The system provisions the clinic (status `trial`), the owner account (status `active`, Argon2-hashed password), and one owner membership, atomically in a single transaction.
4. The system records the sign-up event in the clinic's activity log.
5. The system sends the prospective owner to the login screen with a success banner and the new clinic's code prefilled.
6. Use case continues at step 2 (normal login as the new owner).

## Postconditions

### Success Postconditions

- The staff member has an active session and can see only the sections their role permits.
- The login event is recorded in the activity log.

### Failure Postconditions

- No session is created; the staff member remains on the login screen.

## Business Rules

### BR-001: Only Active Accounts May Log In

An account marked inactive cannot be used to log in even with the correct password.

### BR-002: Access Is Role-Scoped

Every section of the application (employee records, evaluation, tasks, procedure preparation, academy, inventory, admin dashboard) is shown or hidden based on the logged-in staff member's role and permissions.

### BR-003: The Owner Account Has Full Access

An account flagged as the owner is automatically granted every section of the application, regardless of any other role setting.

### BR-004: Sign-Up Provisions a Trial Clinic and Owner Membership Atomically

Sign-up creates a clinic in status `trial`, the owner account in status `active`, and one `owner` membership in status `active`, all in a single transaction — a failed step rolls back the whole clinic.

### BR-005: Username Is Unique Within a Clinic

A username is unique within a single clinic (enforced by `(clinic_id, username)`), so the same username can exist in different clinics. Authentication is keyed by `(clinic_slug, username)`, both matched case-insensitively — the clinic code is lowercased before the lookup, since `clinic.slug` is always stored lowercase. Sign-up names the conflicting field (email or the derived clinic slug) on the offending input; a username conflict can't occur through self-service sign-up itself, since each sign-up provisions a brand-new clinic, but the same check will matter once a later use case adds members to an existing clinic.
