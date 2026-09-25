# UC-008 Invoice Policy And Coverage Design

## Scope

Close the UC-008 gaps reported by coverage audit: make invoice photos configurable per clinic, then cover role scoping, stock ledger entries, and queued item deletion.

## Invoice Policy

Add `clinic_settings.invoice_photo_required boolean not null default true` in V29. Existing clinics retain current mandatory-photo behavior.

Expose the field on `ClinicSettingsService.ClinicSettings` and add a focused update method. Add an owner/manager checkbox to the existing clinic settings screen.

`DefaultPurchasingService.receive` reads the policy in its transaction. It rejects a missing invoice photo only when the policy is enabled; it persists `null` otherwise. The receive template marks the file field required only when the policy is enabled.

## Coverage

Add integration coverage for both invoice policy paths. Add browser role fixtures for assistant, receptionist, and manager inventory access. Extend inventory service tests to assert receipt, issue, return, and count ledger records. Add queued item-delete reject and approve cases.

## Boundaries

No new tables, roles, permissions, storage behavior, or approval workflow changes. RLS remains enforced by existing tenant-bound transactions.

## Verification

Run focused unit/integration/browser tests, `mvn verify`, UC-008 coverage audit, then UC-008 manual testing.
