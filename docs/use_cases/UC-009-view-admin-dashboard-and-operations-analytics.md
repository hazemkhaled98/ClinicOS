# Use Case: View Admin Dashboard and Operations Analytics

## Overview

**Use Case ID:** UC-009
**Use Case Name:** View Admin Dashboard and Operations Analytics
**Primary Actor:** Owner/Manager
**Goal:** The owner or manager gets a clinic-wide overview of team performance and operating volume against target, to guide management decisions.
**Status:** Implemented

## Preconditions

- The manager is logged in (UC-001) with admin dashboard access.

## Main Success Scenario

1. The manager opens the admin dashboard.
2. The system shows the current month's operating volume against its target, paced against how much of the month has elapsed.
3. The system shows a summary of every employee's current performance score and tier.
4. The manager records or updates the clinic's operating volume figure for the month.
5. The manager drills into an individual employee's evaluation for more detail (see UC-004).

The employee profile tab also provides the employee's academy qualification report. The overview scorecard links directly to `/evaluation?employee=<id>&month=<month>` for the detailed evaluation.

The operating-volume figure is entered manually and resets as a new monthly period begins.

## Alternative Flows

### A1: No Operating Target Configured

**Trigger:** The clinic has not set a monthly operating target (step 2)
**Flow:**

1. The operating-volume component of the dashboard and of every employee's score is skipped.
2. Use case continues at step 3.

## Postconditions

### Success Postconditions

- The manager has an up-to-date view of clinic performance and operating volume.
- An updated operating volume figure is saved and factored into every employee's evaluation for that month.

### Failure Postconditions

- Not applicable beyond the update in step 4, which simply is not saved on failure.

## Business Rules

### BR-001: Operating Volume Is Paced Against the Month

The dashboard compares actual operating volume so far against a target that is scaled to how many working days of the month have elapsed, not the full monthly target.

### BR-002: Only the Manager or Owner Sees the Admin Dashboard

Assistants and receptionists do not have access to this clinic-wide view.
