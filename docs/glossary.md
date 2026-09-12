# Glossary

Shared vocabulary for planning and revamp discussions. Terms as used by the existing system (`index_original.html`) and its AIUP documentation, so spec and code agree on meaning.

## Roles

| Term | Meaning |
|------|---------|
| **Owner** | Top-level account. Full access to all sections automatically (BR-G03). Not scored — no personal evaluation (BR-G17). |
| **Manager** | Approves tasks, checklists, stock edits, and returns (BR-G12, BR-G18, BR-G26). Sees admin dashboard (BR-G30) and all inventory areas (BR-G25). |
| **Assistant** | Clinical staff role. Sees own tasks, tray stock/issuing, own procedures (BR-G25). |
| **Receptionist** | Front-desk staff role. Sees ordering/receiving/returns/suppliers (BR-G25). |
| **Employee** | Any staff account in the clinic; their role is the membership role (BR-G04). |

## Evaluation & Performance

| Term | Meaning |
|------|---------|
| **Frozen month** | A past calendar month whose evaluation was calculated once and is now write-once/immutable (BR-G05). |
| **Live month** | The current calendar month; only month that recalculates as new data arrives (BR-G05). |
| **Unlock** | Manager action that clears a frozen month's write-once state, allowing recalculation. |
| **Performance tier** | Bucket a final score falls into: excellent / very good / good / needs improvement (BR-G07). |
| **Manual override** | Manager-entered value for an evaluation category; acts only as a floor, never lowers computed score (BR-G06). |
| **Coverage** | Share of evaluation categories that had data this month; categories with no data are excluded, not zeroed (BR-G15). |
| **Operating volume** | Procedure/case throughput metric, one of the six scored categories, compared against a pace-adjusted target (BR-G29). |
| **Initiative** | Self-proposed/extra work category blended into final score. |
| **evalSnap** | Legacy storage key prefix for a frozen evaluation snapshot (`evalSnap:EMPID:MONTH`). |

## Tasks & Attendance

| Term | Meaning |
|------|---------|
| **Task definition** | Recurring task template (daily/weekly/monthly/custom frequency) an employee is scored against (BR-G09). |
| **Task assignment** | One-off task, manager-assigned or employee-proposed, requiring manager approval to count (BR-G12). |
| **Archived task** | Soft-deleted task definition; no longer scored even if previously required (BR-G10). |
| **Grace period** | Configured window after shift start during which arrival is not counted late (BR-G08). |
| **Daily record** | Log of a single employee's work/attendance for one day. |
| **Self-check** | Employee's own submitted proof/checklist entry for a day (`selfcheck:EMPID:DATE`). |

## Procedures & Checklists

| Term | Meaning |
|------|---------|
| **Prep checklist** | Reusable procedure checklist template; needs manager approval before use (BR-G18) and at least one section/item (BR-G19). |
| **Prep run** | One execution of a checklist on a given day; progress tracked per day (BR-G20). |
| **Procedure** | Clinical procedure type with a defined cost model (materials + labor + doctor fee, BR-G28). |
| **Procedure case** | One instance of a procedure performed, driving cost and operating-volume figures. |

## Academy / Training

| Term | Meaning |
|------|---------|
| **Curriculum** | Set of training units for a role: shared core + role-specific units (BR-G21). |
| **Practical step** | Curriculum step needing verifier-confirmed proof photo (BR-G22). |
| **Verifier** | Staff member (typically manager) who confirms a practical step's proof photo. |
| **Certificate** | Issued only after passing the final exam, where the curriculum requires one (BR-G24). |

## Inventory

| Term | Meaning |
|------|---------|
| **Store** | Central clinic stock location (as opposed to tray). |
| **Tray** | Per-chair/procedure working stock location, distinct from store. |
| **Approval request (inventory)** | Queued stock edit/delete or supplier return awaiting manager approval (BR-G26). |
| **Supplier order** | Purchase order to a supplier for inventory items. |
| **Return** | Quantity sent back to a supplier against a delivery; capped at quantity received and not yet returned (BR-G27). |
| **Operations invoice** | Monthly financial rollup of inventory/procedure costs (`invoice:MONTH`). |

## System / Data

| Term | Meaning |
|------|---------|
| **app_data** | Single Supabase table storing all entities as key/value rows (legacy architecture). |
| **Key-prefix convention** | Legacy scheme for typing rows inside `app_data`, e.g. `daily:EMPID:DATE`, `assign:EMPID`, `inv:state`, `prep:*`. |
| **Config version** | Versioned config blob controlling weights, grace periods, tiers, etc.; offline clients reconcile against it. |
| **Offline queue** | Client-side localStorage queue of writes made while offline, synced on reconnect. |
