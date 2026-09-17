# Implement UC-006 preparation checklists

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the tenant-safe checklist, template-import, approval, and daily-run workflow from UC-006.

**Architecture:** Add immutable global template tables seeded by Flyway, then copy templates into the existing tenant-owned checklist tables. Expose one PrepChecklistService API and keep jOOQ in prep.internal; render Thymeleaf pages with HTMX mutations and server-side validation.

**Tech Stack:** Spring Boot 4.1, jOOQ 3.21, PostgreSQL/Flyway, Spring Modulith, Thymeleaf, HTMX, Alpine.js, Tailwind CSS 4, JUnit/AssertJ, Testcontainers, Playwright.

**Spec:** docs/superpowers/specs/2026-09-17-uc006-prep-checklists-design.md

## Global Constraints

- Every DB operation runs in a Spring-managed transaction with TenantContext bound before the first query.
- app_rw may select global template rows but must not mutate them.
- Only draft and approved are valid checklist states; edits and unapproval clear approval.
- Checklist saves require at least one section containing at least one non-blank item.
- Use existing DESIGN.md tokens/components and RTL logical properties; never copy raw Stitch hex or physical-direction utilities.
- Do not add essential-item fields, appointment/patient links, offline behavior, or template-admin screens.
- Do not add code comments.

---

### Task 1: Add the immutable template catalog

**Files:**
- Create: apps/api/src/main/resources/db/migration/V23__prep_templates.sql
- Create: apps/api/src/test/java/com/clinicos/prep/internal/PrepTemplateCatalogIT.java

**Interfaces:**
- Produces generated jOOQ tables PREP_TEMPLATE, PREP_TEMPLATE_SECTION, and PREP_TEMPLATE_ITEM.

- [ ] Step 1: Write the catalog integration test

Extend AbstractPostgresIntegrationTest and assert the three seeded codes, ordered child rows, and that app_rw cannot insert a template.

    assertThat(dsl.selectCount().from(PREP_TEMPLATE).fetchOne(0)).isEqualTo(3);
    assertThat(dsl.select(PREP_TEMPLATE.CODE).from(PREP_TEMPLATE)
            .orderBy(PREP_TEMPLATE.DISPLAY_ORDER).fetch(PREP_TEMPLATE.CODE))
            .containsExactly("examination", "anesthesia", "endo");

- [ ] Step 2: Run the focused schema check

Run from apps/api: mvn -q -Dtest=PrepTemplateCatalogIT test

Expected: FAIL because PREP_TEMPLATE does not exist.

- [ ] Step 3: Create and seed the catalog

Create immutable global tables with stable text codes, Arabic names, and display-order columns. Add foreign keys, uniqueness on each sibling order, and SELECT-only grants for app_rw. Seed examination, anesthesia, and endo using the Arabic copy and sections/items from ClinicOS Design/07_prep_checklist_templates/code.html.

- [ ] Step 4: Rerun the catalog IT

Run from apps/api: mvn -q -Dtest=PrepTemplateCatalogIT test

Expected: PASS, including the three template rows, child ordering, and read-only privilege assertion.

- [ ] Step 5: Regenerate jOOQ and commit

Run from apps/api: mvn -q -DskipTests generate-sources

    git add apps/api/src/main/resources/db/migration/V23__prep_templates.sql apps/api/src/test/java/com/clinicos/prep/internal/PrepTemplateCatalogIT.java
    git commit -m "feat: add prep template catalog"

### Task 2: Define the prep service API and validation model

**Files:**
- Create: apps/api/src/main/java/com/clinicos/prep/api/PrepChecklistService.java
- Create: apps/api/src/test/java/com/clinicos/prep/api/PrepChecklistValidationTest.java

**Interfaces:**
- Produces the records and operations consumed by Tasks 3–6.

- [ ] Step 1: Write failing validation tests

    @Test
    void saveRejectsNoSections() {
        var request = new ChecklistRequest("كشف", List.of());
        assertThatThrownBy(() -> PrepChecklistService.validate(request))
                .hasMessage("أضف قسمًا واحدًا على الأقل");
    }

    @Test
    void saveRejectsSectionWithoutItems() {
        var request = new ChecklistRequest("كشف",
                List.of(new SectionRequest("عام", List.of())));
        assertThatThrownBy(() -> PrepChecklistService.validate(request))
                .hasMessage("يجب أن يحتوي كل قسم على عنصر واحد على الأقل");
    }

- [ ] Step 2: Run the focused test

Run: mvn -q -Dtest=PrepChecklistValidationTest test

Expected: FAIL because the API and validator are absent.

- [ ] Step 3: Define the minimal public contract

    public interface PrepChecklistService {
        List<Checklist> list(UUID clinicId);
        Checklist get(UUID clinicId, UUID checklistId);
        List<Template> templates();
        Checklist importTemplate(UUID clinicId, Actor actor, String templateCode);
        Checklist save(UUID clinicId, Actor actor, UUID checklistId, ChecklistRequest request);
        void archive(UUID clinicId, Actor actor, UUID checklistId);
        void approve(UUID clinicId, Actor actor, UUID checklistId);
        void unapprove(UUID clinicId, Actor actor, UUID checklistId);
        Run today(UUID clinicId, Actor actor, UUID checklistId);
        Run toggle(UUID clinicId, Actor actor, UUID checklistId, UUID itemId, boolean checked);
        Run reset(UUID clinicId, Actor actor, UUID checklistId);
    }

    record Actor(UUID membershipId, String roleCode, UUID employeeId) {}
    record ChecklistRequest(String name, List<SectionRequest> sections) {}
    record SectionRequest(String title, List<ItemRequest> items) {}
    record ItemRequest(String name) {}
    record Checklist(UUID id, String name, String status,
            UUID approvedBy, List<Section> sections) {}
    record Section(UUID id, String title, List<Item> items) {}
    record Item(UUID id, String name, boolean checked) {}
    record Template(String code, String name, List<Section> sections) {}
    record Run(UUID id, LocalDate runDate, int checkedCount,
            int totalCount, List<Section> sections) {}

    static void validate(ChecklistRequest request) { }

The validator rejects null/blank names, empty sections, blank section titles, empty item lists, and blank item names, and trims accepted text.

- [ ] Step 4: Run tests and commit

Run: mvn -q -Dtest=PrepChecklistValidationTest test

    git add apps/api/src/main/java/com/clinicos/prep/api/PrepChecklistService.java apps/api/src/test/java/com/clinicos/prep/api/PrepChecklistValidationTest.java
    git commit -m "feat: define prep checklist service contract"

### Task 3: Implement tenant-safe persistence and business rules

**Files:**
- Create: apps/api/src/main/java/com/clinicos/prep/internal/DefaultPrepChecklistService.java
- Create: apps/api/src/test/java/com/clinicos/prep/internal/DefaultPrepChecklistServiceIT.java

**Interfaces:**
- Consumes PrepChecklistService and generated jOOQ tables.
- Produces the Spring PrepChecklistService bean.

- [ ] Step 1: Write integration tests named BRG18_editClearsApproval, BRG18_assistantCannotApprove, BRG19_emptyStructureRejected, BRG20_sameDayResumes, BRG20_nextDayIsIndependent, importTemplateDeepCopiesRows, archiveHidesChecklist, draftCannotRun, clinicBCannotReadClinicA, and resetClearsChecks.

    @Test
    void BRG18_editClearsApproval() {
        var edited = service.save(clinicA, assistant, checklistId,
                new ChecklistRequest("كشف معدل",
                    List.of(new SectionRequest("عام",
                        List.of(new ItemRequest("أداة"))))));
        assertThat(edited.status()).isEqualTo("draft");
        assertThat(edited.approvedBy()).isNull();
    }

- [ ] Step 2: Run the IT and verify failure

Run: mvn -q -Dtest=DefaultPrepChecklistServiceIT test

Expected: FAIL because the service bean and persistence are absent.

- [ ] Step 3: Implement reads and writes

Inject DSLContext and TransactionTemplate. List/load only non-archived rows, order sections/items by display_order, and apply clinic predicates to every tenant-owned query. Read global templates without a clinic predicate, then insert imported rows under the current clinic in one transaction.

- [ ] Step 4: Implement transitions and authorization

save replaces children atomically and sets status draft plus null approval fields. approve/unapprove require owner or manager and approval revalidates BR-G19. today/toggle/reset require an active employee actor and an approved checklist. Archived rows reject all mutations. Never trust a browser-supplied employee identifier.

- [ ] Step 5: Implement daily progress

Create or select the unique clinic/checklist/employee/LocalDate.now run. Upsert one prep_run_item per current item; checked_at is OffsetDateTime.now when checked and null otherwise. Reset deletes all current run items. On a unique-key race, re-read the winning run.

- [ ] Step 6: Run IT and commit

Run: mvn -q -Dtest=DefaultPrepChecklistServiceIT test

Expected: PASS, including clinic-A/clinic-B negative assertions.

    git add apps/api/src/main/java/com/clinicos/prep/internal/DefaultPrepChecklistService.java apps/api/src/test/java/com/clinicos/prep/internal/DefaultPrepChecklistServiceIT.java
    git commit -m "feat: implement prep checklist persistence"

### Task 4: Add the preparation controller and HTMX routes

**Files:**
- Create: apps/api/src/main/java/com/clinicos/ui/PrepController.java
- Create: apps/api/src/test/java/com/clinicos/ui/PrepControllerTest.java
- Modify: apps/api/src/main/java/com/clinicos/ui/package-info.java only if prep is missing from allowed dependencies.

**Interfaces:**
- Consumes PrepChecklistService, LayoutModel, ActivityLogService, SessionKeys, and HttpSession.
- Produces model names checklists, checklist, templates, run, and fieldErrors.

- [ ] Step 1: Write failing MVC tests

Test GET /prep, GET /prep/templates, GET/POST checklist new/edit, template import, approve, unapprove, archive, run item toggle, and reset. Assert views/fragments, service arguments, redirects, CSRF handling, and missing-session denial.

- [ ] Step 2: Run the MVC test

Run: mvn -q -Dtest=PrepControllerTest test

Expected: FAIL because PrepController is absent.

- [ ] Step 3: Implement these mappings

    @GetMapping("/prep")
    @GetMapping("/prep/templates")
    @PostMapping("/prep/templates/{code}/import")
    @GetMapping({"/prep/checklists/new", "/prep/checklists/{id}/edit"})
    @PostMapping({"/prep/checklists", "/prep/checklists/{id}"})
    @PostMapping("/prep/checklists/{id}/approve")
    @PostMapping("/prep/checklists/{id}/unapprove")
    @PostMapping("/prep/checklists/{id}/archive")
    @GetMapping("/prep/checklists/{id}/run")
    @PostMapping("/prep/checklists/{id}/run/items/{itemId}")
    @PostMapping("/prep/checklists/{id}/run/reset")

Build Actor from SessionKeys and resolve the membership-linked employee in the controller. Return Arabic validation errors in-band; the shared head fragment adds the CSRF HTMX header.

- [ ] Step 4: Add activity logging and error translation

Log create, import, edit, approve, unapprove, archive, toggle, and reset. Map not-found, forbidden, invalid-structure, draft-run, and archived errors to existing Toasts/form-error patterns without exposing raw SQL.

- [ ] Step 5: Run MVC tests and commit

Run: mvn -q -Dtest=PrepControllerTest test

    git add apps/api/src/main/java/com/clinicos/ui/PrepController.java apps/api/src/test/java/com/clinicos/ui/PrepControllerTest.java apps/api/src/main/java/com/clinicos/ui/package-info.java
    git commit -m "feat: add prep checklist routes"

### Task 5: Build desktop and mobile checklist views

**Files:**
- Create: apps/api/src/main/resources/templates/prep.html
- Create: apps/api/src/main/resources/templates/prep-templates.html
- Create: apps/api/src/main/resources/templates/prep-editor.html
- Create: apps/api/src/main/resources/templates/prep-run.html
- Modify: apps/api/src/main/styles/components.css only for a missing shared component.
- Test: existing TemplateHygieneTest and CssHygieneTest.

**Interfaces:**
- Consumes controller models and existing head/drawer/topbar/toast/confirm fragments.
- Produces selectors prep-checklist-card, prep-template-card, prep-editor-section, and prep-run-item for browser tests.

- [ ] Step 1: Add hygiene assertions

Assert shared RTL head usage, CSRF headers on mutations, no raw hex/arbitrary colors, and no physical-direction classes/properties.

- [ ] Step 2: Implement list and template pages

Use design screens 06, 07, and 08. Render approve/unapprove controls only for owner/manager. Use DESIGN.md tokens and existing card/button classes.

- [ ] Step 3: Implement the editor

Follow screen 09: ordered sections/items, add/remove controls, inline errors, save, and archive confirmation. Alpine may manipulate rows; server input remains authoritative. Omit essential checkboxes.

- [ ] Step 4: Implement the daily run

Follow screen 10: status, checked/total counter, grouped items, touch-friendly checkboxes, reset confirmation, and HTMX fragment swaps. Keep the page usable with HTMX only.

- [ ] Step 5: Compile and verify styles

Run from apps/api: npm run build:css
Run from apps/api: mvn -q -Dtest=TemplateHygieneTest,CssHygieneTest test

Expected: PASS with no RTL/token violations.

- [ ] Step 6: Commit views

    git add apps/api/src/main/resources/templates/prep.html apps/api/src/main/resources/templates/prep-templates.html apps/api/src/main/resources/templates/prep-editor.html apps/api/src/main/resources/templates/prep-run.html apps/api/src/main/styles/components.css apps/api/target/classes/static/css/app.css
    git commit -m "feat: add prep checklist screens"

### Task 6: Exercise UC-006 end to end

**Files:**
- Create: apps/api/src/test/java/com/clinicos/ui/UC006PrepareAndRunProcedureChecklistsIT.java
- Modify: apps/api/src/test/java/com/clinicos/TestFixtures.java only for reusable prep seed helpers.

**Interfaces:**
- Consumes real routes and seeded catalog.
- Produces browser evidence for the main scenario and A1–A4.

- [ ] Step 1: Write browser scenarios

Use AbstractBrowserIT and unique clinic/user fixtures. Cover import, edit-to-pending, manager approval, run toggle/reload, reset, unapprove blocking, archive confirmation, and clinic-B isolation at desktop and mobile viewports.

- [ ] Step 2: Run the browser test

Run: mvn -q -Dtest=UC006PrepareAndRunProcedureChecklistsIT test

Expected: PASS at both viewport sizes.

- [ ] Step 3: Commit browser coverage

    git add apps/api/src/test/java/com/clinicos/ui/UC006PrepareAndRunProcedureChecklistsIT.java apps/api/src/test/java/com/clinicos/TestFixtures.java
    git commit -m "test: cover UC-006 checklist workflow"

### Task 7: Close verification and phase tracking

**Files:**
- Modify: docs/roadmap.md
- Modify: docs/use_cases/UC-006-prepare-and-run-procedure-checklists.md only to correct its stale status.

- [ ] Step 1: Run full verification

    npm run build:css
    mvn verify

Expected: all unit, integration, browser, Modulith, template, and CSS tests pass.

- [ ] Step 2: Run /coverage-check for UC-006

It must map main steps 1–7, A1–A4, BR-G18, BR-G19, and BR-G20 to implementation and tests. Add only missing behavior/tests, then rerun until clean.

- [ ] Step 3: Run /manual-testing for UC-006

Run it only after coverage is clean. Fix reproducible defects, rerun focused tests, and repeat manual testing until clean.

- [ ] Step 4: Update status

Mark Phase 5 bullets complete only after gates pass. Change its status row to done only after its PR is merged; until then leave it in progress.

- [ ] Step 5: Commit documentation

    git add docs/roadmap.md docs/use_cases/UC-006-prepare-and-run-procedure-checklists.md
    git commit -m "docs: close UC-006 phase tracking"

## Self-review

- Coverage: Tasks 1–3 cover schema, validation, persistence, BR-G18–20, approval, daily isolation, reset, archive, and RLS. Tasks 4–6 cover every main step and A1–A4 in UI and browser tests. Task 7 supplies coverage and manual-testing gates.
- Placeholder scan: no TBD/TODO or unspecified implementation steps remain.
- Type consistency: Task 2 defines every shared service record used by Tasks 3–6.
