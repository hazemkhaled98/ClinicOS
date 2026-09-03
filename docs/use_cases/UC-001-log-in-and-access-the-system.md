# Use Case: Log In and Access the System

## Overview

**Use Case ID:** UC-001
**Use Case Name:** Log In and Access the System
**Primary Actor:** Any Staff Member (Owner/Manager, Assistant, Receptionist)
**Goal:** A staff member authenticates with their credentials and reaches the part of the system their role is permitted to use.
**Status:** Implemented

## Preconditions

- The staff member has an active account created by the owner or manager.
- The clinic's shared data has finished loading from the cloud (or the device falls back to offline queuing).

## Main Success Scenario

1. The staff member opens the application and is shown the login screen.
2. The staff member enters their username and password.
3. The system verifies the credentials against the active accounts.
4. The system starts a session for the staff member and remembers it on the device.
5. The system determines which sections of the application the staff member's role is allowed to see.
6. The system opens the first section the staff member is permitted to use and shows the navigation menu for their role.
7. The staff member logs out when finished, ending the session.

## Alternative Flows

### A1: Invalid Credentials

**Trigger:** The username is not found, the account is inactive, or the password does not match (step 3)
**Flow:**

1. The system shows an error message and keeps the staff member on the login screen.
2. Use case ends.

### A2: No Confirmed Connection to the Shared Data

**Trigger:** The device cannot confirm it has the latest shared data from the cloud (step 1)
**Flow:**

1. The system warns the staff member that changes will be saved on the device only until the connection is confirmed.
2. Use case continues at step 2.

### A3: Returning to a Previously Open Section

**Trigger:** The staff member has logged in before on this device (step 6)
**Flow:**

1. The system reopens the section the staff member last used, if their role still permits it.
2. Use case continues at step 7.

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
