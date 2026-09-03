# Use Case: Complete Onboarding Academy Training

## Overview

**Use Case ID:** UC-007
**Use Case Name:** Complete Onboarding Academy Training
**Primary Actor:** Assistant or Receptionist (trainee), verified by Owner/Manager
**Goal:** A new employee works through the role-specific onboarding curriculum, has their practical steps verified with photo evidence, passes a final exam, and receives a certificate of completion.
**Status:** Implemented

## Preconditions

- The staff member is logged in (UC-001).
- The clinic's academy curriculum for the trainee's role has been configured (shared core units plus role-specific units).

## Main Success Scenario

1. The trainee opens the academy section and selects their onboarding curriculum.
2. The trainee works through each curriculum unit for their role (shared core units plus role-specific units).
3. The trainee uploads a photo as proof of completing a practical step.
4. A verifier (the manager or another authorized reviewer) opens the trainee's curriculum, reviews the submitted photos, and confirms or rejects each one.
5. Once all required units are verified, the trainee starts the final exam, made up of questions drawn from the completed units.
6. The trainee submits the exam and the system scores it immediately.
7. On a passing score, the system issues a certificate of completion naming the trainee and their curriculum.

## Alternative Flows

### A1: A Verified Step Is Rejected

**Trigger:** The verifier rejects a submitted proof photo (step 4)
**Flow:**

1. The step remains incomplete and the trainee is prompted to resubmit a photo.
2. Use case continues at step 3.

### A2: Failing the Exam

**Trigger:** The trainee's exam score is below the passing threshold (step 6)
**Flow:**

1. The system records the failed attempt and lets the trainee retake the exam.
2. Use case continues at step 5.

### A3: Manager Edits the Curriculum

**Trigger:** The manager updates the academy's units, questions, or which role they apply to (step 1)
**Flow:**

1. The system applies the updated curriculum to all trainees going forward.
2. Use case ends.

## Postconditions

### Success Postconditions

- The trainee's progress, verified photos, exam result, and (if passed) certificate are saved.
- A passing exam and completed verification make the certificate available to view.

### Failure Postconditions

- Progress up to the point of failure or rejection is saved; the certificate is not issued.

## Business Rules

### BR-001: The Curriculum Is Role-Specific

A trainee's curriculum is the shared core units plus the units defined for their specific role (assistant or receptionist).

### BR-002: Practical Steps Require Photo Verification

A practical curriculum step is not considered complete until a verifier confirms the submitted proof photo.

### BR-003: The Exam Draws from Completed Units

The final exam's question pool is built from the curriculum units the trainee has covered.

### BR-004: A Certificate Requires a Passing Exam

The completion certificate is only made available once the trainee has passed the final exam (where an exam is required by the curriculum).
