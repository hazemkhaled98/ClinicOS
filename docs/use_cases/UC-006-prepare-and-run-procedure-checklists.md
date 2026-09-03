# Use Case: Prepare and Run Procedure Checklists

## Overview

**Use Case ID:** UC-006
**Use Case Name:** Prepare and Run Procedure Checklists
**Primary Actor:** Assistant or Receptionist (with Owner/Manager approval)
**Goal:** Staff prepare a step-by-step tool and material checklist for a clinical procedure, get it approved, and follow it while preparing an actual session so nothing is forgotten.
**Status:** Implemented

## Preconditions

- The staff member is logged in (UC-001) with access to session preparation.

## Main Success Scenario

1. The staff member opens the procedure checklist section and sees existing checklists.
2. The staff member creates a new checklist, naming the procedure and adding sections and items in order, or imports a ready-made checklist from the built-in template library.
3. The staff member saves the checklist.
4. The system marks the new or edited checklist as pending manager approval.
5. The manager reviews and approves the checklist.
6. When preparing an actual session, a staff member opens the approved checklist and checks off each item as it is completed.
7. The system saves the checklist run's progress for that day so it can be resumed.

## Alternative Flows

### A1: Using a Ready-Made Template

**Trigger:** The staff member imports a checklist from the template library instead of building one from scratch (step 2)
**Flow:**

1. The system copies the template's sections and items into a new checklist for the clinic.
2. Use case continues at step 3.

### A2: Manager Rejects or Un-Approves a Checklist

**Trigger:** The manager withdraws approval from a previously approved checklist (step 5)
**Flow:**

1. The checklist returns to pending status and cannot be used to prepare a session until re-approved.
2. Use case ends.

### A3: Resetting a Checklist Run

**Trigger:** The staff member wants to start the checklist over during the same day (step 6)
**Flow:**

1. The system clears all checked items for the current run.
2. Use case continues at step 6.

### A4: Deleting a Checklist

**Trigger:** The staff member removes a checklist they created (step 1)
**Flow:**

1. The system asks for confirmation before permanently removing the checklist.
2. Use case ends.

## Postconditions

### Success Postconditions

- The checklist and its approval status are saved and available to staff.
- Progress on a checklist run for the day is saved.

### Failure Postconditions

- The checklist or run progress is not saved.

## Business Rules

### BR-001: A New or Edited Checklist Requires Manager Approval

A checklist cannot be used to prepare a live session until the manager has approved it.

### BR-002: A Checklist Needs at Least One Section and Item

A checklist cannot be saved empty; it must have at least one section with at least one item.

### BR-003: Checklist Runs Are Tracked Per Day

Progress on checking off a procedure's items is tracked separately for each day it is run.
