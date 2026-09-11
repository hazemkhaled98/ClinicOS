package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

/**
 * UC-002: Manage Employees and Roles, exercised end-to-end in a real browser
 * (blackbox -- no service/DSLContext access). Slice 2a covers the settings tab:
 * the employee roster lives under {@code /admin-dashboard/settings} as
 * inline-editable HTMX rows.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC002ManageEmployeesAndRolesIT extends AbstractBrowserIT {

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

    private void login(String username, String password, String clinicCode) {
        page().getByLabel("كود العيادة").fill(clinicCode);
        page().getByLabel("اسم المستخدم").fill(username);
        page().getByLabel("كلمة المرور").fill(password);
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("دخول")).click();
    }

    private void openAdminSettings() {
        page().navigate(getUrl() + "admin-dashboard");
        page().waitForURL(url -> url.contains("admin-dashboard/settings"));
    }

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String seedOwnerWithClinic(String username, String rawPassword) throws Exception {
        String slug = "clinic-" + uniqueSuffix();
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic " + username, slug);
            UUID userId = TestFixtures.insertUser(
                    connection, clinicId, username, passwordEncoder.encode(rawPassword), "active");
            TestFixtures.insertMembership(connection, clinicId, userId, "owner");
        }
        return slug;
    }

    @Nested
    @DisplayName("Step: Employee roster (settings tab)")
    class EmployeeRoster {

        @Test
        @DisplayName("Adding an employee renders a row and re-displays it after reload")
        void addEmployeePersists() throws Exception {
            String username = "owner-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";
            String clinicSlug = seedOwnerWithClinic(username, rawPassword);

            page().navigate(getUrl() + "login");
            login(username, rawPassword, clinicSlug);
            openAdminSettings();

            page().getByText("👥 الموظفون").waitFor();
            page().getByText("⚙️ أوزان مكونات التقييم").waitFor();
            page().getByText("🕐 دوام العيادة").waitFor();
            page().getByText("🏅 شرائح الحافز").waitFor();
            page().getByPlaceholder("اسم الموظف").fill("محمود سمير");
            page().getByPlaceholder("المرتب").last().fill("5000");
            page().getByPlaceholder("الحافز").last().fill("1500");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("+ إضافة موظف")).click();

            page().locator(".clinicos-employee-row").last().waitFor();
            PlaywrightAssertions.assertThat(
                    page().locator(".clinicos-employee-row").last().getByText("محمود سمير")).isVisible();

            page().reload();
            page().locator(".clinicos-employee-row").last().waitFor();
            PlaywrightAssertions.assertThat(
                    page().locator(".clinicos-employee-row").last().getByText("محمود سمير")).isVisible();
        }

        @Test
        @DisplayName("Inline row edit updates role, pay and incentive")
        void editRowUpdatesFields() throws Exception {
            String username = "owner-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";
            String clinicSlug = seedOwnerWithClinic(username, rawPassword);

            page().navigate(getUrl() + "login");
            login(username, rawPassword, clinicSlug);
            openAdminSettings();

            page().getByPlaceholder("اسم الموظف").fill("محمود سمير");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("+ إضافة موظف")).click();
            page().locator(".clinicos-employee-row").last().waitFor();

            page().locator(".clinicos-employee-row").last()
                    .locator("select[name=staffRoleCode]").selectOption("receptionist");
            page().locator(".clinicos-employee-row").last()
                    .locator("input[name=basePay]").fill("5200");
            page().locator(".clinicos-employee-row").last()
                    .locator("input[name=maxIncentive]").fill("2000");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ")).last().click();

            page().locator(".clinicos-employee-row").last().waitFor();
            assertThat(page().locator(".clinicos-employee-row").last()
                    .locator("select[name=staffRoleCode]").inputValue()).isEqualTo("receptionist");
            String payValue = page().locator(".clinicos-employee-row").last()
                    .locator("input[name=basePay]").inputValue();
            assertThat(payValue.replace(".00", "")).isEqualTo("5200");
            String incValue = page().locator(".clinicos-employee-row").last()
                    .locator("input[name=maxIncentive]").inputValue();
            assertThat(incValue.replace(".00", "")).isEqualTo("2000");
        }

        @Test
        @DisplayName("Archiving an employee removes the row and shows the empty state")
        void archiveRemovesRow() throws Exception {
            String username = "owner-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";
            String clinicSlug = seedOwnerWithClinic(username, rawPassword);

            page().navigate(getUrl() + "login");
            login(username, rawPassword, clinicSlug);
            openAdminSettings();

            page().getByPlaceholder("اسم الموظف").fill("محمود سمير");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("+ إضافة موظف")).click();
            page().locator(".clinicos-employee-row").last().waitFor();

            page().onDialog(dialog -> dialog.accept());
            page().locator(".clinicos-employee-row").last()
                    .locator("button[aria-label='حذف الموظف']").click();

            PlaywrightAssertions.assertThat(
                    page().getByText("لا يوجد موظفون بعد. أضف أول موظف من النموذج أدناه.")).isVisible();
            assertThat(page().locator(".clinicos-employee-row").count()).isZero();
        }
    }

    @Nested
    @DisplayName("Step: Goals & gamification tab")
    class GoalsAndGamification {

        @Test
        @DisplayName("Saving each card persists values and survives reload")
        void savingCardsPersistsAcrossReload() throws Exception {
            String username = "owner-" + uniqueSuffix();
            String rawPassword = "correct-horse-battery-staple";
            String clinicSlug = seedOwnerWithClinic(username, rawPassword);

            page().navigate(getUrl() + "login");
            login(username, rawPassword, clinicSlug);
            page().navigate(getUrl() + "admin-dashboard/goals");
            dumpThresholds("initial-load");

            page().locator("input[name=showLeaderboard]").check();
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ")).first().click();
            page().locator("#settings-card input[name=showLeaderboard]").waitFor();
            assertThat(page().locator("input[name=showLeaderboard]").isChecked()).isTrue();

            page().locator("input[name=titles]").first().fill("مهارة التبسم");
            page().locator("input[name=targets]").first().fill("12");
            page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ الأهداف")).click();
            page().locator("#goals-card input[name=titles]").first().waitFor();
            assertThat(page().locator("input[name=titles]").first().inputValue()).isEqualTo("مهارة التبسم");
            assertThat(page().locator("input[name=targets]").first().inputValue()).isEqualTo("12");

            badgeThresholdInput("نجم الأسبوع").fill("7");
            var thrResponse = page().waitForResponse(r -> r.url().contains("thresholds"),
                    () -> page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ الشروط")).click());
            String thrBody;
            try {
                thrBody = "[" + thrResponse.status() + "] " + thrResponse.text();
            } catch (Exception e) {
                thrBody = "[" + thrResponse.status() + "] ERR " + e + " url=" + thrResponse.url();
            }
            System.out.println("== [POST /thresholds] " + thrBody);
            page().locator("#thresholds-card input[name=thresholds]").first().waitFor();
            dumpThresholds("after-save-fragment");
            assertThat(badgeThresholdValue("نجم الأسبوع")).isEqualTo("7");

            page().reload();
            dumpThresholds("after-reload");
            dumpDbOrder(clinicSlug, "reload-raw");
            dumpDbOrder(clinicSlug, "reload-raw-again");
            page().navigate(getUrl() + "admin-dashboard/goals");
            dumpThresholds("after-2nd-navigation");
            assertThat(page().locator("input[name=showLeaderboard]").isChecked()).isTrue();
            assertThat(page().locator("input[name=titles]").first().inputValue()).isEqualTo("مهارة التبسم");
            assertThat(page().locator("input[name=targets]").first().inputValue()).isEqualTo("12");
            assertThat(badgeThresholdValue("نجم الأسبوع")).isEqualTo("7");
        }

        private void dumpDbOrder(String slug, String label) throws Exception {
            try (Connection connection = DriverManager.getConnection(
                    PostgresTestSupport.POSTGRES.getJdbcUrl(),
                    PostgresTestSupport.POSTGRES.getUsername(),
                    PostgresTestSupport.POSTGRES.getPassword())) {
                var rows = connection.createStatement().executeQuery(
                        "select b.name, b.threshold from badge_threshold b join clinic c on c.id = b.clinic_id where c.slug = '" + slug + "' order by b.name asc");
                StringBuilder sb = new StringBuilder();
                while (rows.next()) {
                    sb.append(rows.getString(1)).append('=').append(rows.getInt(2)).append(',');
                }
                System.out.println("== [db:" + label + "] " + sb);
            }
        }

        private com.microsoft.playwright.Locator badgeThresholdInput(String badgeName) {
            return page().locator("#thresholds-card form > div:has(input[name=names][value='" + badgeName + "'])")
                    .locator("input[name=thresholds]");
        }

        private String badgeThresholdValue(String badgeName) {
            return badgeThresholdInput(badgeName).inputValue();
        }

        private void dumpThresholds(String label) {
            String dump = page().evalOnSelectorAll("#thresholds-card form > div",
                    "els => els.map(e => { const r = e.querySelector('input[name=names]'); const t = e.querySelector('input[name=thresholds]'); return r ? r.value + '=' + (t ? t.value : '?') : '??'; })")
                    .toString();
            System.out.println("== [" + label + "] " + dump + " :: docOrderFirst=" + page().locator("input[name=thresholds]").first().inputValue());
        }
    }
}