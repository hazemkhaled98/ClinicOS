# Business Rules — Consolidated

Single source of truth for business rules across all use cases. Global IDs assigned; original per-UC numbering shown for traceability. Where two UCs described the same rule from different angles, they are merged into one entry with multiple sources.

| ID | Rule | Sources |
|----|------|---------|
| BR-G01 | Inactive account cannot log in even with correct password. | UC-001 BR-001 |
| BR-G02 | Every app section (employee records, evaluation, tasks, prep, academy, inventory, admin dashboard) shown/hidden per logged-in staff role and permissions. | UC-001 BR-002 |
| BR-G03 | Owner account gets every section automatically, overriding any other role setting. | UC-001 BR-003 |
| BR-G04 | An employee's role is their clinic membership role (member → role); it drives permissions, the task list, academy curriculum and inventory areas. | UC-002 BR-001, UC-008 BR-001 |
| BR-G05 | Any calendar month before the current one is closed and its evaluation frozen (write-once) once calculated; only unlocking clears the freeze. | UC-002 BR-002, UC-004 BR-001 |
| BR-G06 | Manager's manual override for an evaluation category only raises the computed score, never lowers it (floor, not ceiling). | UC-002 BR-003 |
| BR-G07 | Monthly incentive is a percentage of max incentive, keyed to performance tier (excellent / very good / good / needs improvement) from final score. | UC-002 BR-004 |
| BR-G08 | Arrival within configured grace period after shift start is not counted late. | UC-003 BR-001 |
| BR-G09 | Recurring task frequency (daily/weekly/monthly/custom) governs how completion is measured, not a daily check. | UC-003 BR-002 |
| BR-G10 | Archived (soft-deleted) task is not scored, even if previously required. | UC-003 BR-003 |
| BR-G11 | Some tasks require a proof photo to count as complete. | UC-003 BR-004 |
| BR-G12 | One-off assigned task (manager- or employee-proposed) counts toward score only after manager approval. | UC-004 BR-002 |
| BR-G13 | Scoring order for assigned tasks: on-time > late > undone. | UC-004 BR-003 |
| BR-G14 | Final monthly score is weighted blend of task completion, technical, behavioral, initiative, attendance, and (if configured) operating volume, using clinic-configured weights. | UC-004 BR-004 |
| BR-G15 | A category with no data is excluded from the weighted average (not scored as zero); exclusion shown as reduced coverage. | UC-004 BR-005 |
| BR-G16 | Evaluation view is always scoped to the logged-in employee's own record. | UC-005 BR-001 |
| BR-G17 | Owner has no personal evaluation — owner is not scored. | UC-005 BR-002 |
| BR-G18 | New/edited checklist unusable for a live prep session until manager approves it. | UC-006 BR-001 |
| BR-G19 | Checklist must have at least one section with at least one item; cannot save empty. | UC-006 BR-002 |
| BR-G20 | Checklist run progress tracked separately per day it is run. | UC-006 BR-003 |
| BR-G21 | Trainee curriculum = shared core units + units for their specific role. | UC-007 BR-001 |
| BR-G22 | Practical curriculum step needs verifier-confirmed proof photo to count complete. | UC-007 BR-002 |
| BR-G23 | Final exam question pool built only from curriculum units the trainee has covered. | UC-007 BR-003 |
| BR-G24 | Completion certificate available only after passing final exam (where curriculum requires one). | UC-007 BR-004 |
| BR-G25 | Inventory access scoped by role: assistant → tray stock/issuing/own procedures/item catalogue (change filed from the catalogue, approval queue stays manager-only); receptionist → ordering/receiving/returns/suppliers; manager → everything + clinic-wide analytics/approvals. | UC-008 BR-001 |
| BR-G26 | Stock-affecting edits (edit/delete stock item, supplier return) are queued, take effect only on manager approval. | UC-008 BR-002 |
| BR-G27 | Return quantity against a delivery cannot exceed quantity received and not yet returned. | UC-008 BR-003 |
| BR-G28 | Procedure total cost = issued items (bill of materials) + labor cost + doctor fee (if applicable). | UC-008 BR-004 |
| BR-G29 | Dashboard operating volume compared against target scaled to elapsed working days in month, not full monthly target. | UC-009 BR-001 |
| BR-G30 | Only manager or owner sees the admin dashboard. | UC-009 BR-002 |
| BR-G31 | Sign-up provisions a new clinic (status `trial`) plus its first owner account and owner membership atomically, in one transaction. | UC-001 BR-004 |

## Cross-cutting themes

- **Approval gating** (BR-G12, BR-G18, BR-G26): three independent approval queues (task, checklist, inventory) — same pattern, different domains. Candidate for one shared "approval request" abstraction in the revamped data model.
- **Frozen-month / write-once snapshot** (BR-G05): touches evaluation only today (BR-G05, BR-G14, BR-G15) but is a reusable pattern if other monthly reporting (e.g. inventory invoice, BR-G01 dashboard) needs the same immutability guarantee later.
- **Role-scoped visibility** (BR-G02, BR-G04, BR-G25, BR-G30): repeated per module instead of one central authorization table in the legacy app — worth centralizing in revamp.

## Resolved quality issue

Original reverse-engineering flagged "UC-004 duplicate BR-002" — this was UC-004's own BR-002 title colliding in number (not content) with UC-002's BR-002. No actual duplicate content; both kept as distinct rules above (BR-G05 merges the one true content overlap between UC-002 BR-002 and UC-004 BR-001).
