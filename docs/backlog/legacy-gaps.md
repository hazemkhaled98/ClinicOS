# Legacy Feature Gaps — Backlog

Features present in `index_original.html` but **not** covered by UC-001…UC-009 or the V1–V10 schema. Each entry includes the schema delta needed. Not built in this plan; recorded here so nothing is lost.

| # | Legacy Feature | Schema Delta Needed | Notes |
|---|----------------|---------------------|-------|
| 1 | **Gamification**: level ring, streaks + record, badges, weekly goals, per-scope visibility toggles | `gamification_settings`, `badge_threshold`, `weekly_goal` | UC-002 step 6 configures thresholds; UC-005 steps 3–4 display them — **mandatory, not optional**. Decide at Phase 2 start whether to build or amend UC. |
| 2 | **Working-days calendar & holidays** (clinic-wide or per-employee) | `clinic_holiday`, weekday mask on `clinic_settings` — currently only `working_days_per_month integer` | UC-003 A3 counts a missed *scheduled work day* as absent; BR-G29 paces volume target by working days elapsed — neither expressible from bare integer. **Mandatory**. Decide at Phase 3 start. **Built in Phase 3** (V20 migration + admin UI, slice 3a/3e). |
| 3 | **Geofence attendance** (clinic coords + allowed radius) | lat/lng/radius on `clinic_settings` | Legacy shows map picker for clinic location + radius. |
| 4 | **Manager daily note** (private or shown to employee), rejection reason on a returned task | columns on `daily_record` / `daily_task_completion` | The `🔔` nudge itself is **not** a gap — `notification` table already covers it. |
| 5 | **Doctor as entity** (per-doctor analytics) | `doctor` table — currently `procedure_case.doctor_name text` | Legacy analytics group by doctor name string; a table enables referential integrity and richer analytics. |
| 6 | **Supplier rating, lead days, price map** | columns on `supplier`, plus `supplier_item_price` | Legacy tracks supplier performance metrics. |
| 7 | **Lot number, delivery cost on receipt** | columns on `purchase_order_line` | Legacy receipt form captures lot + delivery cost per line. |
| 8 | **Prep template library**, importable into a clinic | platform-level `prep_template` | UC-006 step 2 and A1 reference "ready-made templates" — **mandatory**. Decide at Phase 5 start. |
| 9 | **Academy quiz kinds**, trainer daily rating, settings toggles, decision report | columns on `academy_question`, `academy_settings` | Legacy has quiz types (`فهم` / `قرار` / `اكتشف الغلط`), trainer daily rating, `تقرير القرار`. |
| 10 | **Image storage policy**, manual/auto purge, JSON backup + restore | retention policy on `attachment`, export job | Legacy PWA had manual cache purge + JSON export/import. |
| 11 | **Reservations / holds on tray stock** | `stock_hold` | Legacy allows reserving tray items for upcoming procedures. |
| 12 | **"Essential item" flag** on a prep checklist item (legacy shows `N أساسي`) | `prep_item.essential boolean` | Visual indicator in legacy prep runs. |
| 13 | **Multi-photo per task** (before/after, multiple angles) | join table (e.g. `daily_task_completion_photo`) instead of the single `photo_id` FK column | `daily_task_completion` (V3) holds one `photo_id` per completion — a task can only ever have one proof photo, even when a manager wants multiple angles. |

---

## Three In-Scope Items Requiring V11 Migration

These are referenced inside UC steps, so they cannot be fully deferred to backlog. Decide at the start of the owning phase whether to build or amend the UC doc.

| Item | UC References | Schema Delta |
|------|---------------|--------------|
| Gamification (weekly/monthly goals, badges, streaks) | UC-002 step 6, UC-005 steps 3–4 | `gamification_settings`, `badge_threshold`, `weekly_goal` |
| Working-day/holiday calendar | UC-003 A3, BR-G29 | `clinic_holiday`, weekday mask on `clinic_settings` |
| Prep template library | UC-006 step 2, UC-006 A1 | platform-level `prep_template` |

---

## Login Identifier (V11)

V11 adds `app_user.username citext unique`. The login field stays `اسم المستخدم` as in the legacy screen. `email` becomes nullable (password reset + invites only). Done: `app_user_credentials_lookup_by_username` and `app_user_memberships_lookup`, both `SECURITY DEFINER` functions reached through a `TenantContext` auth-mode escape — no separate privileged DataSource/role, per the correction in roadmap.md step 7.

---

## Self-Service Sign-Up (V13, Phase 1b) — Deliberate Deferrals

Built as `signup_clinic_with_owner` (a `SECURITY DEFINER` function, the only door for `app_rw` to create a clinic before a tenant exists). Two hardening items were deliberately deferred to Phase 9 — the app must not be publicly reachable before both are added:

| # | Deferred Item | Why It Matters | Notes |
|---|---------------|----------------|-------|
| 13 | **Email verification** on sign-up | Without it, sign-up does not prove ownership of the email address; typos silently orphan an account and the email cannot be used for password reset later. | Needs SMTP + a token table (`signup_verification` or reuse), both far beyond Phase 1b's "zero DDL" scope. |
| 14 | **Sign-up rate limiting / abuse throttling** | A public pre-auth form with instant activation invites mass tenant creation and credential stuffing — each sign-up is also an Argon2 hash for the server to burn. | Needs CAPTCHA and/or per-IP/per-session throttling; also out of the zero-DDL scope. Keyed by both IP and fresh HTTP session. |