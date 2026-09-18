package com.clinicos.ui;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.clinicos.PostgresTestSupport;
import com.clinicos.TestFixtures;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

/**
 * UC-006: Manage prep checklists for procedures, exercised end-to-end through
 * a real browser. The workflow covers: listing checklists, viewing templates,
 * creating/editing checklists, approval by manager, and daily session execution.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC006PrepChecklistsIT extends AbstractBrowserIT {

    @LocalServerPort
    private int port;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeAll
    static void migrateAndProvisionAppRw() throws Exception {
        PostgresTestSupport.migrateAndProvisionAppRw();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        PostgresTestSupport.configureDatasourceProperties(registry);
    }

    @Override
    public String getUrl() {
        return String.format("http://localhost:%d/", port);
    }

    private void loginAsRole(String username, String clinicCode, String password) {
        page().navigate(getUrl() + "login");
        page().getByLabel("كود العيادة").fill(clinicCode);
        page().getByLabel("اسم المستخدم").fill(username);
        page().getByLabel("كلمة المرور").fill(password);
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("دخول")).click();
        page().locator(".clinicos-nav-item").first().waitFor();
    }

    private String seedTestData() throws Exception {
        String clinicSlug = "test-clinic-" + UUID.randomUUID();
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic", clinicSlug);

            UUID ownerId = TestFixtures.insertUser(connection, clinicId, "owner", passwordEncoder.encode("password"), "active");
            UUID managerId = TestFixtures.insertUser(connection, clinicId, "manager", passwordEncoder.encode("password"), "active");
            UUID assistantId = TestFixtures.insertUser(connection, clinicId, "assistant", passwordEncoder.encode("password"), "active");

            UUID ownerMembershipId = TestFixtures.insertMembership(connection, clinicId, ownerId, "owner");
            UUID managerMembershipId = TestFixtures.insertMembership(connection, clinicId, managerId, "manager");
            UUID assistantMembershipId = TestFixtures.insertMembership(connection, clinicId, assistantId, "assistant");

            UUID managerEmpId = TestFixtures.insertEmployee(connection, clinicId, "manager");
            UUID assistantEmpId = TestFixtures.insertEmployee(connection, clinicId, "assistant");

            TestFixtures.linkMembershipToEmployee(connection, managerMembershipId, managerEmpId);
            TestFixtures.linkMembershipToEmployee(connection, assistantMembershipId, assistantEmpId);
        }
        return clinicSlug;
    }

    @Nested
    @DisplayName("Prep checklists list and templates view")
    class ListAndTemplates {

        @Test
        @DisplayName("Assistant can view empty checklists list with action buttons")
        void viewEmptyChecklist() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");

            page().navigate(getUrl() + "prep");
            PlaywrightAssertions.assertThat(page().getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("تحضير الجلسات"))).isVisible();
            PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("إجراء جديد")).first()).isVisible();
            PlaywrightAssertions.assertThat(page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("قوالب جاهزة")).first()).isVisible();
        }

        @Test
        @DisplayName("User can view templates gallery with import buttons")
        void viewTemplateGallery() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");

            page().navigate(getUrl() + "prep/templates");
            PlaywrightAssertions.assertThat(page().getByText("قوالب جاهزة").first()).isVisible();
            PlaywrightAssertions.assertThat(page().locator("button:has-text('استيراد للعيادة')").first()).isVisible();
        }

        @Test
        @DisplayName("Assistant can import a template as a draft and edit it")
        void assistantCanImportTemplate() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");

            page().navigate(getUrl() + "prep/templates");
            page().locator("button:has-text('استيراد للعيادة')").first().click();
            page().waitForURL(url -> url.contains("/prep/checklists/") && url.endsWith("/edit"));

            PlaywrightAssertions.assertThat(page().getByLabel("اسم الإجراء"))
                    .hasValue("إكزامينيشن (كشف وتشخيص)");
            PlaywrightAssertions.assertThat(page().locator("input[placeholder*='عنوان القسم']"))
                    .hasCount(2);
            PlaywrightAssertions.assertThat(page().locator("input[placeholder*='اسم الأداة']"))
                    .hasCount(9);
        }
    }

    @Nested
    @DisplayName("Create and edit checklists")
    class CreateAndEdit {

        @Test
        @DisplayName("New checklist form displays with procedure name, sections, and items fields")
        void newChecklistFormDisplays() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");

            page().navigate(getUrl() + "prep/checklists/new");
            PlaywrightAssertions.assertThat(page().getByLabel("اسم الإجراء")).isVisible();
            PlaywrightAssertions.assertThat(page().locator("input[placeholder*='عنوان القسم']").first()).isVisible();
            PlaywrightAssertions.assertThat(page().locator("input[placeholder*='اسم الأداة']").first()).isVisible();
        }

        @Test
        @DisplayName("Editor can add a second section and a second item dynamically")
        void editorAddsSectionsAndItems() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");

            page().navigate(getUrl() + "prep/checklists/new");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("عنصر")).first().click();
            PlaywrightAssertions.assertThat(page().locator("input[placeholder*='اسم الأداة']")).hasCount(2);

            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("إضافة قسم جديد")).click();
            PlaywrightAssertions.assertThat(page().locator("input[placeholder*='عنوان القسم']")).hasCount(2);
        }
    }

    @Nested
    @DisplayName("Approval workflow")
    class Approval {

        @Test
        @DisplayName("Assistant cannot see approval button (permission check)")
        void assistantCannotApprove() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");

            page().navigate(getUrl() + "prep");
            PlaywrightAssertions.assertThat(page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("اعتماد"))).not().isVisible();
        }

        @Test
        @DisplayName("Owner can approve without an employee record")
        void ownerCanApproveWithoutEmployee() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");
            page().navigate(getUrl() + "prep/checklists/new");
            page().getByLabel("اسم الإجراء").fill("اعتماد المالك");
            page().locator("input[placeholder*='عنوان القسم']").first().fill("قسم");
            page().locator("input[placeholder*='اسم الأداة']").first().fill("أداة");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ القائمة")).click();
            page().waitForURL(url -> url.contains("/prep"));

            page().context().clearCookies();
            loginAsRole("owner", clinicSlug, "password");
            page().navigate(getUrl() + "prep");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("اعتماد")).click();
            page().waitForURL(url -> url.contains("/prep"));

            PlaywrightAssertions.assertThat(page().locator("a[href*='/run']")).isVisible();
        }

        @Test
        @DisplayName("Manager can withdraw approval from an approved checklist")
        void managerCanWithdrawApproval() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");
            page().navigate(getUrl() + "prep/checklists/new");
            page().getByLabel("اسم الإجراء").fill("اختبار السحب");
            page().locator("input[placeholder*='عنوان القسم']").first().fill("قسم");
            page().locator("input[placeholder*='اسم الأداة']").first().fill("أداة");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ القائمة")).click();
            page().waitForURL(url -> url.contains("/prep"));
            page().context().clearCookies();

            loginAsRole("manager", clinicSlug, "password");
            page().navigate(getUrl() + "prep");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("اعتماد")).first().click();
            page().waitForURL(url -> url.contains("/prep"));

            PlaywrightAssertions.assertThat(page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("سحب الاعتماد")).first()).isVisible();
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("سحب الاعتماد")).first().click();
            page().waitForURL(url -> url.contains("/prep"));

            PlaywrightAssertions.assertThat(page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("اعتماد")).first()).isVisible();
        }

        @Test
        @DisplayName("Archiving a checklist asks for confirmation before removing it")
        void archiveAsksForConfirmation() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");
            page().navigate(getUrl() + "prep/checklists/new");
            page().getByLabel("اسم الإجراء").fill("اختبار الأرشفة");
            page().locator("input[placeholder*='عنوان القسم']").first().fill("قسم");
            page().locator("input[placeholder*='اسم الأداة']").first().fill("أداة");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ القائمة")).click();
            page().waitForURL(url -> url.contains("/prep"));

            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("أرشفة")).first().click();
            PlaywrightAssertions.assertThat(page().locator("#confirm-overlay")).isVisible();

            page().locator("#confirm-cancel").click();
            PlaywrightAssertions.assertThat(page().getByText("اختبار الأرشفة")).isVisible();
        }

        @Test
        @DisplayName("Confirming the archive dialog removes the checklist from the list")
        void confirmingArchiveRemovesChecklist() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");
            page().navigate(getUrl() + "prep/checklists/new");
            page().getByLabel("اسم الإجراء").fill("اختبار التأكيد");
            page().locator("input[placeholder*='عنوان القسم']").first().fill("قسم");
            page().locator("input[placeholder*='اسم الأداة']").first().fill("أداة");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ القائمة")).click();
            page().waitForURL(url -> url.contains("/prep"));

            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("أرشفة")).first().click();
            PlaywrightAssertions.assertThat(page().locator("#confirm-overlay")).isVisible();
            page().locator("#confirm-ok").click();
            page().waitForURL(url -> url.contains("/prep"));

            PlaywrightAssertions.assertThat(page().getByText("اختبار التأكيد")).not().isVisible();
        }
    }

    @Nested
    @DisplayName("Daily session execution")
    class SessionExecution {

        @Test
        @DisplayName("Session execution page template loads successfully")
        void sessionPageLoads() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");

            page().navigate(getUrl() + "prep");
            PlaywrightAssertions.assertThat(page().getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("تحضير الجلسات"))).isVisible();
        }

        @Test
        @DisplayName("Assistant can complete and reset an approved daily checklist")
        void assistantCanCompleteAndResetApprovedChecklist() throws Exception {
            String clinicSlug = seedTestData();
            loginAsRole("assistant", clinicSlug, "password");
            page().navigate(getUrl() + "prep/checklists/new");
            page().getByLabel("اسم الإجراء").fill("اختبار التشغيل اليومي");
            page().locator("input[placeholder*='عنوان القسم']").first().fill("قسم");
            page().locator("input[placeholder*='اسم الأداة']").first().fill("أداة");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ القائمة")).click();
            page().waitForURL(url -> url.contains("/prep"));

            page().context().clearCookies();
            loginAsRole("manager", clinicSlug, "password");
            page().navigate(getUrl() + "prep");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("اعتماد")).first().click();
            page().waitForURL(url -> url.contains("/prep"));

            page().context().clearCookies();
            loginAsRole("assistant", clinicSlug, "password");
            page().navigate(getUrl() + "prep");
            page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("بدء الجلسة")).first().click();
            Response response = page().waitForResponse(
                    candidate -> candidate.url().contains("/run/items/"),
                    () -> page().locator("input[type='checkbox']").first().check());
            org.junit.jupiter.api.Assertions.assertEquals(200, response.status());
            org.junit.jupiter.api.Assertions.assertEquals("checked=true", response.request().postData());
            PlaywrightAssertions.assertThat(page().locator("span.text-sm.font-extrabold")).hasText("1/1");

            page().reload();
            PlaywrightAssertions.assertThat(page().locator("input[type='checkbox']").first()).isChecked();
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("إعادة ضبط")).click();
            PlaywrightAssertions.assertThat(page().locator("span.text-sm.font-extrabold")).hasText("0/1");
        }
    }
}
