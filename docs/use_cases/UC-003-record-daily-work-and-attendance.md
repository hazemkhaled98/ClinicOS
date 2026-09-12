# Use Case: Record Daily Work and Attendance

## Overview

**Use Case ID:** UC-003
**Use Case Name:** Record Daily Work and Attendance
**Primary Actor:** Assistant or Receptionist
**Goal:** An employee checks in for the day, completes their recurring and assigned tasks, and provides proof so their performance is measured accurately.
**Status:** Implemented

## Preconditions

- The staff member is logged in (UC-001) as an assistant or receptionist.
- The clinic's task list for the employee's role has been configured (UC-002).

## Main Success Scenario

1. The employee opens their daily tasks view.
2. The employee checks in, recording their arrival time.
3. The employee marks each recurring task (daily, weekly, monthly, or custom-frequency) as done as they complete it during the day.
4. The employee attaches a photo as proof for a task where proof is expected.
5. The employee reviews any one-off tasks assigned to them and marks assigned tasks as done, attaching proof where required.
6. The employee proposes a new one-off task on their own initiative when they notice something that needs attention.
7. The employee checks out at the end of the day, recording their departure time.
8. The system saves each update immediately and reflects it in the employee's ongoing performance figures.

## Alternative Flows

### A1: Arriving Late

**Trigger:** The recorded check-in time is after the shift start plus the grace period (step 2)
**Flow:**

1. The system counts the day as a late arrival when scoring attendance.
2. Use case continues at step 3.

### A2: Leaving Early

**Trigger:** The recorded check-out time is before the shift end (step 7)
**Flow:**

1. The system counts the day as an early departure when scoring attendance.
2. Use case continues at step 8.

### A3: No Check-In Recorded for a Work Day

**Trigger:** The employee does not check in on a scheduled work day (step 2)
**Flow:**

1. The system counts the day as absent when scoring attendance.
2. Use case ends.

### A4: Self-Proposed Task Requires Manager Approval

**Trigger:** The employee proposes a new task (step 6)
**Flow:**

1. The proposed task is marked pending until the manager approves or rejects it (see UC-004).
2. Use case continues at step 7.

### A5: Offline Recording — Not Applicable

**Status:** Out of scope by decision (see `docs/roadmap.md`, "Offline mode" row and the UC-001 deviation note). ClinicOS is a server-rendered Thymeleaf app with no client-side offline queue — this flow does not exist and will not be implemented.

## Postconditions

### Success Postconditions

- The day's task completions and attendance times are saved and available for evaluation.
- The activity log records that the employee reviewed and logged their daily work.

### Failure Postconditions

- The day's entry is not saved.

## Business Rules

### BR-001: Attendance Scoring Uses a Grace Period

An arrival within the configured grace period after shift start is not counted as late.

### BR-002: Recurring Tasks Follow Their Configured Frequency

A task is expected daily, weekly, monthly, or on a custom interval; completion is measured against that frequency, not every single day.

### BR-003: A Task Marked Archived No Longer Counts

An employee is not scored on a task the manager has archived (soft-deleted), even if it was previously required.

### BR-004: Proof Photos May Be Required

Certain tasks require a photo to be counted as complete.
