# Use Case: Manage Clinic Inventory and Procedure Costs

## Overview

**Use Case ID:** UC-008
**Use Case Name:** Manage Clinic Inventory and Procedure Costs
**Primary Actor:** Owner/Manager, Assistant, or Receptionist (each scoped to different areas)
**Goal:** Staff keep clinic stock accurate — ordering, receiving, issuing, and returning supplies — while the manager tracks what each procedure actually costs and earns.
**Status:** Implemented

## Preconditions

- The staff member is logged in (UC-001) with an inventory role (manager, assistant, or reception) granting access to at least one inventory area.
- Stock items and suppliers have been set up in the system.

## Main Success Scenario

1. The staff member opens the inventory section and sees only the areas their inventory role permits (stock levels, orders, issuing to procedures, returns, suppliers, approvals, or clinic-wide analytics for the manager).
2. A receptionist places an order with a supplier for items running low, or records goods received against an existing order.
3. An assistant takes items from tray/store stock to use in a procedure, recording which procedure and doctor the items were issued for.
4. A receptionist records a return of unused or defective items to a supplier.
5. Changes that affect committed stock (editing or deleting an item, or a return) are queued for manager approval rather than applied immediately.
6. The manager reviews and approves or rejects each pending change.
7. The manager reviews procedure-level cost and profitability analytics: material cost, labor cost, doctor fees, and net margin per procedure and per doctor.
8. The system keeps a running log of every stock movement (count changes, issues, receipts, returns) for audit.

## Alternative Flows

### A1: Manager Rejects a Pending Change

**Trigger:** The manager rejects a queued edit, deletion, or return instead of approving it (step 6)
**Flow:**

1. The pending change is discarded and stock levels remain as they were before the request.
2. Use case continues at step 7.

### A2: Stock Falls Below Its Reorder Alert

**Trigger:** An item's store or tray quantity drops below its configured alert threshold (step 1)
**Flow:**

1. The system flags the item as needing reorder in the relevant inventory views.
2. Use case continues at step 2.

### A3: Insufficient Stock to Issue

**Trigger:** A requested quantity for a procedure exceeds available tray stock (step 3)
**Flow:**

1. The system limits the issued quantity to what is actually available.
2. Use case continues at step 8.

### A4: Recording an Invoice Photo for Received Goods

**Trigger:** The clinic requires a photo of the supplier invoice when goods are received (step 2)
**Flow:**

1. The staff member attaches the invoice photo to the receipt.
2. Use case continues at step 8.

## Postconditions

### Success Postconditions

- Stock levels, orders, issues, and returns reflect the approved changes.
- Procedure cost and profitability figures reflect the latest issued items and prices.
- Every stock-affecting action is recorded in the inventory log.

### Failure Postconditions

- The requested change remains pending approval, or is discarded on rejection.

## Business Rules

### BR-001: Inventory Access Is Scoped by Role

An assistant sees only tray stock, issuing, their own procedures, and the item catalogue, from which a stock change is filed (the reach step 5 requires; the approval queue itself stays manager-only); a receptionist sees only ordering, receiving, returns, and suppliers; the manager sees every area plus clinic-wide analytics and approvals.

### BR-002: Stock-Affecting Edits Require Manager Approval

Editing or deleting a stock item, and any return to a supplier, is queued and only takes effect once the manager approves it.

### BR-003: A Return Cannot Exceed What Was Received

The quantity returned against a delivery cannot exceed the quantity actually received and not yet returned.

### BR-004: Procedure Cost Includes Materials, Labor, and Doctor Fees

A procedure's total cost combines the cost of items issued (its bill of materials), a labor cost, and, where applicable, the treating doctor's fee.
