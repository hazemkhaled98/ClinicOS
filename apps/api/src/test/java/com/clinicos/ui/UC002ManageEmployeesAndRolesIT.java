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
}