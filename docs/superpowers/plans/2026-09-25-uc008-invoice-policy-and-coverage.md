# UC-008 Invoice Policy And Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make invoice photos optional per clinic while closing UC-008 role, ledger, and approval-path coverage.

**Architecture:** V29 adds a tenant-scoped `clinic_settings` flag defaulting to mandatory, preserving deployed behavior. The clinic-settings aggregate owns configuration; purchasing reads it inside its transaction and the receive view reflects it. Existing service and browser tests cover the remaining UC behavior.

**Tech Stack:** Java 25, Spring Boot, Thymeleaf, jOOQ, Flyway, JUnit 5, Testcontainers, Playwright.

**Spec:** `docs/superpowers/specs/2026-09-25-uc008-invoice-policy-and-coverage-design.md`

## Global Constraints

- Add no dependencies, tables, roles, or permissions.
- Preserve RLS: every DB interaction remains in Spring-managed tenant transactions.
- Default is `invoice_photo_required = true` for existing and new clinics.
- Use Arabic validation text: `صورة الفاتورة مطلوبة`.
- Use TDD and commit each task.

---

### Task 1: Invoice Policy Persistence And Settings API

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V29__invoice_photo_policy.sql`
- Modify: `apps/api/src/main/java/com/clinicos/clinicconfig/api/ClinicSettingsService.java`
- Modify: `apps/api/src/main/java/com/clinicos/clinicconfig/internal/DefaultClinicSettingsService.java`
- Test: `apps/api/src/test/java/com/clinicos/clinicconfig/internal/DefaultClinicSettingsServiceIT.java`

**Interfaces:**
- Produces: `boolean invoicePhotoRequired()` on `ClinicSettings`.
- Produces: `void updateInvoicePhotoRequired(UUID clinicId, boolean required)`.

- [ ] **Step 1: Write failing integration tests**

```java
assertThat(service.get(clinicId).invoicePhotoRequired()).isTrue();
service.updateInvoicePhotoRequired(clinicId, false);
assertThat(service.get(clinicId).invoicePhotoRequired()).isFalse();
```

- [ ] **Step 2: Run the test red**

Run: `mvn test -Dtest=DefaultClinicSettingsServiceIT`

- [ ] **Step 3: Add V29 and API implementation**

```sql
alter table clinic_settings
  add column invoice_photo_required boolean not null default true;
```

```java
void updateInvoicePhotoRequired(UUID clinicId, boolean required);
```

- [ ] **Step 4: Run the test green**

Run: `mvn test -Dtest=DefaultClinicSettingsServiceIT`

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V29__invoice_photo_policy.sql apps/api/src/main/java/com/clinicos/clinicconfig apps/api/src/test/java/com/clinicos/clinicconfig/internal/DefaultClinicSettingsServiceIT.java
```

### Task 2: Receipt Validation And Settings UI

**Files:**
- Modify: `apps/api/src/main/java/com/clinicos/inventory/internal/DefaultPurchasingService.java`
- Modify: `apps/api/src/main/java/com/clinicos/inventory/PurchasingService.java`
- Modify: `apps/api/src/main/java/com/clinicos/ui/PurchasingController.java`
- Modify: `apps/api/src/main/java/com/clinicos/ui/ClinicSettingsController.java`
- Modify: `apps/api/src/main/java/com/clinicos/ui/AdminController.java`
- Modify: `apps/api/src/main/resources/templates/admin/settings.html`
- Modify: `apps/api/src/main/resources/templates/inventory-receive.html`
- Test: `apps/api/src/test/java/com/clinicos/inventory/internal/DefaultPurchasingServiceIT.java`
- Test: `apps/api/src/test/java/com/clinicos/ui/ClinicSettingsControllerTest.java`

**Interfaces:**
- Consumes: `ClinicSettingsService.invoicePhotoRequired()` and `updateInvoicePhotoRequired` from Task 1.
- Produces: receipt acceptance with `invoicePhotoId == null` only when policy is false.

- [ ] **Step 1: Write failing receipt-policy tests**

```java
assertThatThrownBy(() -> service.receive(clinicId, actor, orderId, lines, null))
  .hasMessage("صورة الفاتورة مطلوبة");
settings.updateInvoicePhotoRequired(clinicId, false);
assertThat(service.receive(clinicId, actor, orderId, lines, null).status()).isEqualTo(PoStatus.received);
```

- [ ] **Step 2: Run tests red**

Run: `mvn test -Dtest=DefaultPurchasingServiceIT,ClinicSettingsControllerTest`

- [ ] **Step 3: Implement minimal policy read and controls**

```java
if (invoicePhotoId == null && settings.get(clinicId).invoicePhotoRequired()) {
    throw missing("صورة الفاتورة مطلوبة");
}
```

Add the existing settings-card form pattern for one boolean checkbox and set the receive template's file `required` attribute from the model policy.

- [ ] **Step 4: Run tests green and compile CSS**

Run: `npm run build:css`

Run: `mvn test -Dtest=DefaultPurchasingServiceIT,ClinicSettingsControllerTest,TemplateHygieneTest,CssHygieneTest`

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/clinicos/inventory apps/api/src/main/java/com/clinicos/ui apps/api/src/main/resources/templates/admin/settings.html apps/api/src/main/resources/templates/inventory-receive.html apps/api/src/test/java/com/clinicos
```

### Task 3: UC-008 Role, Ledger, And Deletion Coverage

**Files:**
- Modify: `apps/api/src/test/java/com/clinicos/ui/UC008InventoryFoundationIT.java`
- Modify: `apps/api/src/test/java/com/clinicos/inventory/internal/DefaultInventoryServiceIT.java`

**Interfaces:**
- Consumes existing role permission defaults, append-only `stock_movement`, and item change-request APIs.
- Produces coverage for BR-G25, BR-G26, and UC-008 postcondition audit entries.

- [ ] **Step 1: Write failing tests**

```java
// Browser: receptionist sees orders/receive/returns/suppliers but not tray/issue.
// Browser: manager sees approvals and analytics.
// Integration: receipt, issue, return, and count each create expected movement reason and quantity.
// Integration: queued delete remains after reject and is removed only after approve.
```

- [ ] **Step 2: Run tests red**

Run: `mvn verify -Dtest=DefaultInventoryServiceIT,UC008InventoryFoundationIT -DfailIfNoSpecifiedTests=false -DskipITs=false`

- [ ] **Step 3: Add fixtures and assertions only**

Keep production code unchanged unless a failing scenario proves an implementation defect.

- [ ] **Step 4: Run tests green**

Run: `mvn verify -Dtest=DefaultInventoryServiceIT,UC008InventoryFoundationIT -DfailIfNoSpecifiedTests=false -DskipITs=false`

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/test/java/com/clinicos/ui/UC008InventoryFoundationIT.java apps/api/src/test/java/com/clinicos/inventory/internal/DefaultInventoryServiceIT.java
```

### Task 4: Full Verification

**Files:**
- Verify only.

- [ ] **Step 1: Run full build**

Run: `mvn verify`

- [ ] **Step 2: Audit UC-008**

Run `/coverage-check UC-008 both work in progress` and retain report.

- [ ] **Step 3: Manual test UC-008**

Run `/manual-testing UC-008` against locally running app with seeded test users.

- [ ] **Step 4: Commit documentation only if audit changes status**

```bash
git status --short
```
