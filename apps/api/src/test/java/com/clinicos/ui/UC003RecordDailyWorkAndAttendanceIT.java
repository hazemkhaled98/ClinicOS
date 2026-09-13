package com.clinicos.ui;

import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalTime;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

/**
 * UC-003: Record Daily Work and Attendance, exercised end-to-end in a real
 * browser against the {@code /employees} self-service daily-work screen.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC003RecordDailyWorkAndAttendanceIT extends AbstractBrowserIT {

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

    private String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private void login(String username, String password, String clinicCode) {
        page().getByLabel("كود العيادة").fill(clinicCode);
        page().getByLabel("اسم المستخدم").fill(username);
        page().getByLabel("كلمة المرور").fill(password);
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("دخول")).click();
    }

    private String seedAssistantShiftEnded(String username, String rawPassword) throws Exception {
        String slug = "clinic-" + uniqueSuffix();
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic " + username, slug);
            UUID userId = TestFixtures.insertUser(
                    connection, clinicId, username, passwordEncoder.encode(rawPassword), "active");
            UUID membershipId = TestFixtures.insertMembership(connection, clinicId, userId, "assistant");
            UUID employeeId = TestFixtures.insertEmployee(connection, clinicId, "منى أحمد");
            TestFixtures.linkMembershipToEmployee(connection, membershipId, employeeId);
            TestFixtures.seedRolePermissionDefaults(connection, clinicId);
            DSL.using(connection, SQLDialect.POSTGRES)
                    .update(EMPLOYEE)
                    .set(EMPLOYEE.CUSTOM_SHIFT, true)
                    .set(EMPLOYEE.SHIFT_START, LocalTime.MIN)
                    .set(EMPLOYEE.SHIFT_END, LocalTime.MIN)
                    .where(EMPLOYEE.ID.eq(employeeId))
                    .execute();
            DSL.using(connection, SQLDialect.POSTGRES)
                    .update(CLINIC_SETTINGS)
                    .set(CLINIC_SETTINGS.WORKING_WEEKDAYS, new Short[] { 1, 2, 3, 4, 5, 6, 7 })
                    .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                    .execute();
        }
        return slug;
    }

    private String seedAssistantWithTasks(String username, String rawPassword, String nonPhotoTaskName,
            String photoTaskName) throws Exception {
        String slug = "clinic-" + uniqueSuffix();
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic " + username, slug);
            UUID userId = TestFixtures.insertUser(
                    connection, clinicId, username, passwordEncoder.encode(rawPassword), "active");
            UUID membershipId = TestFixtures.insertMembership(connection, clinicId, userId, "assistant");
            UUID employeeId = TestFixtures.insertEmployee(connection, clinicId, "منى أحمد");
            TestFixtures.linkMembershipToEmployee(connection, membershipId, employeeId);
            TestFixtures.seedRolePermissionDefaults(connection, clinicId);
            TestFixtures.insertTaskDefinition(connection, clinicId, "assistant", nonPhotoTaskName,
                    "fanni", "daily", false);
            TestFixtures.insertTaskDefinition(connection, clinicId, "assistant", photoTaskName,
                    "fanni", "daily", true);
        }
        return slug;
    }

    private Locator taskRow(String taskName) {
        return page().locator("div.flex.items-center.gap-3.py-3", new Page.LocatorOptions().setHasText(taskName));
    }

    @Test
    @DisplayName("Employee with no check-in after shift end sees the absence warning")
    void noCheckInAfterShiftEndShowsAbsenceWarning() throws Exception {
        String username = "emp-" + uniqueSuffix();
        String rawPassword = "correct-horse-battery-staple";
        String clinicSlug = seedAssistantShiftEnded(username, rawPassword);

        page().navigate(getUrl() + "login");
        login(username, rawPassword, clinicSlug);
        page().waitForURL(url -> url.contains("/employees"));

        PlaywrightAssertions.assertThat(page().getByText("⛔ وقت الدوام انتهى — اليوم ده هيتحسب غياب.")).isVisible();
        PlaywrightAssertions.assertThat(page().getByText("🔒 المهام مقفولة")).isVisible();
    }

    @Test
    @DisplayName("Employee checks in, confirms a task, proposes an assignment, then checks out")
    void recordsDailyWorkAndAttendance() throws Exception {
        String username = "emp-" + uniqueSuffix();
        String rawPassword = "correct-horse-battery-staple";
        String nonPhotoTask = "تعقيم الأدوات";
        String photoTask = "تصوير قبل وبعد";
        String clinicSlug = seedAssistantWithTasks(username, rawPassword, nonPhotoTask, photoTask);

        page().navigate(getUrl() + "login");
        login(username, rawPassword, clinicSlug);
        page().waitForURL(url -> url.contains("/employees"));

        PlaywrightAssertions.assertThat(page().getByText("🔒 المهام مقفولة")).isVisible();

        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("سجّل الآن")).first().click();
        page().getByText("🔒 المهام مقفولة").waitFor(new Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.DETACHED));
        PlaywrightAssertions.assertThat(page().locator("text=/في الميعاد|متأخّر|بعد الدوام/")).isVisible();

        Locator nonPhotoRow = taskRow(nonPhotoTask);
        nonPhotoRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("✔ أكّدها")).click();
        PlaywrightAssertions.assertThat(taskRow(nonPhotoTask).getByText("✔ أكّدتها")).isVisible();

        Locator photoRow = taskRow(photoTask);
        assertThat(photoRow.locator("input[type=file][name=photo]").count()).isGreaterThan(0);

        String proposedName = "مهمة مقترحة " + uniqueSuffix();
        page().locator("form[hx-post='/employees/assignments/propose'] input[name=name]").fill(proposedName);
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("اقترح للمدير")).click();

        PlaywrightAssertions.assertThat(page().locator("#toast-root")).containsText("تم إرسال الاقتراح للمدير");
        PlaywrightAssertions.assertThat(page().getByText("في انتظار موافقة المدير")).isVisible();

        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("سجّل الآن")).click();
        PlaywrightAssertions.assertThat(page().locator("text=/ميعاد كامل|انصراف مبكّر/")).isVisible();
    }
}
