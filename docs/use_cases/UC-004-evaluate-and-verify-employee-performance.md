# Use Case: Evaluate and Verify Employee Performance

## Overview

**Use Case ID:** UC-004
**Use Case Name:** Evaluate and Verify Employee Performance
**Primary Actor:** Owner/Manager
**Goal:** The manager reviews each employee's daily work, confirms proof, rates the qualitative parts of performance, and manages one-off assignments so the monthly evaluation is accurate and fair.
**Status:** Implemented

## Preconditions

- The manager is logged in (UC-001) with evaluation access.
- Employees have logged daily work and attendance for the period under review (UC-003).

## Main Success Scenario

1. The manager opens the evaluation and verification section and selects an employee and month.
2. The manager reviews the employee's daily task completions, attendance, and any attached proof photos.
3. The manager rates the employee's technical performance and behavioral performance for the day.
4. The manager reviews any one-off tasks assigned to or proposed by the employee, and approves or rejects each one, including any proof photo attached.
5. The manager assigns a new one-off task to an employee when needed, optionally with a due date.
6. The system recalculates the employee's monthly performance score, incorporating task completion, attendance, technical and behavioral ratings, initiative, and (for the current month) operating volume.
7. The manager reviews the computed score and tier for the employee.

## Alternative Flows

### A1: Rejecting a Proposed or Assigned Task

**Trigger:** The manager rejects a task instead of approving it (step 4)
**Flow:**

1. The task is not counted toward the employee's task completion or initiative score.
2. Use case continues at step 5.

### A2: Reviewing a Closed (Frozen) Month

**Trigger:** The selected month is before the current calendar month (step 1)
**Flow:**

1. The system shows the frozen evaluation snapshot from when the month was first closed rather than recalculating it live.
2. To change a frozen month's numbers, the manager must unlock it first (see UC-002 A3).
3. Use case ends for scoring purposes; review continues read-only.

### A3: Setting a Manual Floor Value

**Trigger:** The manager believes an automatically computed category score understates the employee's performance (step 3 or 6)
**Flow:**

1. The manager sets a manual value for that category (see UC-002 A2).
2. Use case continues at step 6.

## Postconditions

### Success Postconditions

- The employee's ratings, task approvals, and assignments for the period are saved.
- The employee's monthly evaluation score reflects the manager's review.
- The review is recorded in the activity log.

### Failure Postconditions

- The manager's changes are not saved, or are queued locally pending connection.

## Business Rules

### BR-001: Only the Current Month Is Live-Scored

Only the current calendar month's evaluation recalculates as new data comes in; earlier months are frozen (see UC-002 BR-002).

### BR-002: Every Assigned Task Needs Manager Approval

A one-off task, whether assigned by the manager or proposed by the employee, only counts toward the score once the manager approves it.

### BR-003: On-Time Completion Scores Higher

An approved assigned task completed by its due date scores higher than one completed late, which in turn scores higher than one left undone.

### BR-004: The Final Score Is a Weighted Blend

The monthly score blends task completion, technical rating, behavioral rating, initiative, attendance, and (where an operating target is configured) operating volume, using the clinic's configured category weights.

### BR-005: Missing Categories Reduce Coverage, Not the Score Directly

A category with no data yet is excluded from the weighted average rather than counted as zero, and its exclusion is shown as reduced evaluation coverage.
