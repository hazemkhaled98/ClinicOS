# Use Case: View My Performance Evaluation

## Overview

**Use Case ID:** UC-005
**Use Case Name:** View My Performance Evaluation
**Primary Actor:** Assistant or Receptionist
**Goal:** An employee (who is not the owner) checks their own current standing, score breakdown, goals and recognition so they know where they stand and what to improve.
**Status:** Implemented

## Preconditions

- The employee is logged in (UC-001) and linked to an employee record.

## Main Success Scenario

1. The employee opens their own evaluation view.
2. The system shows the employee's current monthly score and its breakdown by category (task completion, technical, behavioral, initiative, attendance, and operating volume where applicable).
3. The system shows the employee's progress toward their personal weekly and monthly goals.
4. The system shows any badges, streaks, or motivational recognition the employee has earned.
5. The employee reviews warnings or coverage gaps (categories not yet rated) affecting their score.

## Alternative Flows

### A1: Viewing a Past Month

**Trigger:** The employee selects a month before the current one (step 1)
**Flow:**

1. The system shows the frozen evaluation snapshot for that month rather than a live calculation.
2. Use case continues at step 2.

### A2: No Data Yet This Month

**Trigger:** The employee has not logged any daily work yet in the selected month (step 2)
**Flow:**

1. The system shows a motivational placeholder instead of a score.
2. Use case ends.

## Postconditions

### Success Postconditions

- The employee has seen their current evaluation standing.

### Failure Postconditions

- Not applicable — this use case is read-only.

## Business Rules

### BR-001: An Employee Can Only View Their Own Evaluation

The evaluation shown is always scoped to the logged-in employee's own record, never another employee's.

### BR-002: The Owner Does Not Use This View

An account flagged as the owner does not have a personal evaluation, since the owner is not scored.
