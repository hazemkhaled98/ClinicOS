# Use Case: Manage Employees and Roles

## Overview

**Use Case ID:** UC-002
**Use Case Name:** Manage Employees and Roles
**Primary Actor:** Owner/Manager
**Goal:** The owner or manager keeps the roster of employees, their roles, pay, shifts and evaluation settings up to date so performance can be tracked accurately.
**Status:** Implemented

## Preconditions

- The staff member is logged in (UC-001) with owner or manager access to employee records.

## Main Success Scenario

1. The manager opens the employee records section.
2. The manager selects an employee from the list, or starts registering a new one.
3. The manager enters or edits the employee's name, role (assistant or receptionist), base pay, maximum incentive, and work shift.
4. The manager saves the changes.
5. The system stores the updated employee record and reflects it immediately in evaluation, tasks, academy and inventory sections that depend on employee role.
6. The manager reviews or adjusts clinic-wide settings that affect every employee's evaluation: the task list per role, evaluation category weights, monthly operating target, grace period for lateness, and gamification/badge thresholds.
7. The manager reviews or edits which user accounts exist, which employee each account is linked to, and what permissions each account/role has.

## Alternative Flows

### A1: Removing or Deactivating an Employee

**Trigger:** The manager marks an employee's account inactive instead of deleting history (step 3)
**Flow:**

1. The system keeps the employee's historical evaluation records intact but the account can no longer log in.
2. Use case continues at step 4.

### A2: Adjusting a Manual Performance Override

**Trigger:** The manager sets a manual "floor" value for one of an employee's evaluation categories for the current month (step 6)
**Flow:**

1. The system uses the manager's manual value whenever it is higher than the automatically computed value for that category.
2. Use case continues at step 7.

### A3: Unlocking a Closed Month's Evaluation

**Trigger:** The manager requests recalculation of a month that has already been frozen (step 6)
**Flow:**

1. The system discards the frozen evaluation snapshot for that employee and month.
2. The system recalculates the evaluation from current data the next time it is viewed, and freezes it again with the new numbers.
3. The system records who unlocked the month and when.
4. Use case continues at step 7.

## Postconditions

### Success Postconditions

- The employee roster and clinic-wide evaluation settings reflect the manager's changes.
- The change is recorded in the activity log.

### Failure Postconditions

- The employee record or settings remain unchanged.

## Business Rules

### BR-001: Two Employee Roles

An employee is either an assistant or a receptionist; the role determines which task list, academy curriculum, and inventory areas apply to them.

### BR-002: Evaluation Months Freeze Automatically

Any calendar month before the current one is closed and its evaluation is calculated once and frozen (write-once); only unlocking clears the freeze.

### BR-003: Manual Overrides Act Only as a Floor

A manager's manually entered value for an evaluation category never lowers the automatically computed score — it only raises it if the computed value is lower.

### BR-004: Incentive Pay Is Tied to the Performance Tier

An employee's monthly incentive is a percentage of their maximum incentive, determined by which performance tier (excellent, very good, good, needs improvement) their final score falls into.
