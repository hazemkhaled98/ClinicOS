# Feature Suggestions — Post-Main-Use-Cases Backlog

Features to look into **after** UC-001…UC-009 are implemented. Unlike `legacy-gaps.md` (things already in `index_original.html`), these are new-product ideas. Each entry notes the schema delta so discovery cost stays low. Not built in this plan.

| # | Priority | Feature | Schema Delta | Notes |
|---|----------|---------|--------------|-------|
| 1 | High | **Notification Center — live** | `notification` table already exists (covers the `🔔` nudge). Add `notification_read` join table and a real-time channel (WebSocket/SSE). | Foundation for the rest: leave-request approval toasts, task approvals, activity nudges. Today there is no notification UI at all. |
| 2 | High | **Holiday / leave requests** — employee submits, manager/owner approves, approver logged | `leave_request` (`employee_id`, `status pending/approved/rejected`, `approved_by`, `approved_at`, `reason`, date range). `clinic_holiday` (V20) is clinic-wide; this is per-employee and needs the approval workflow + audit. | Should emit a notification (item 1) on submit and on decision. |
| 3 | Medium | **Editable clinic slug** — set at signup, changeable via admin dashboard | Unique constraint already on `clinic.slug`. Need an admin form + slug-change audit row (`activity_log`). | If slugs ever appear in URLs, a change must redirect old slugs — decide at build time. |
| 4 | Low | **Dynamic clinic branding** — replace hardcoded `عيادتي` with the clinic name registered in sign-up | None — pass `clinic.name` through the session into templates. | ~20 hardcoded `عيادتي` strings across templates (`topbar.html`, `drawer.html`, `section.html`, per-view kickers, page titles). |
| 5 | Medium | **Save feedback** — show a visible success toast after HTMX form saves | None — reusable client-side toast component. | Confirms immediate persistence without forcing users to scan the page for changed values. |
| 6 | Medium | **Actionable empty states** — link empty inventory, ordering, and academy screens to their required setup action | None. | Explain the missing prerequisite and provide one permitted next action instead of a dead-end empty screen. |
