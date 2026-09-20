package com.clinicos.ui;

import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static com.clinicos.shared.jooq.tables.DailyRecord.DAILY_RECORD;
import static com.clinicos.shared.jooq.tables.DailyTaskCompletion.DAILY_TASK_COMPLETION;
import static com.clinicos.shared.jooq.tables.SelfCheck.SELF_CHECK;
import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.jooq.DSLContext;
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
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
import com.clinicos.shared.jooq.enums.TaskReviewStatus;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

/**
 * UC-009: View Admin Dashboard and Operations Analytics — a manager opens the
 * clinic-wide overview, sees the paced operating volume and team summary,
 * records the month's operating volume, and drills into an employee.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC009ViewAdminDashboardAndOperationsAnalyticsIT extends AbstractBrowserIT {

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

    private Seed seedManagerWithScoredEmployee(boolean withVolumeTarget) throws Exception {
        String slug = "clinic-" + uniqueSuffix();
        String username = "mgr-" + uniqueSuffix();
        String rawPassword = "manager-pass";
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic " + username, slug);
            TestFixtures.insertMembership(connection, clinicId,
                    TestFixtures.insertUser(connection, clinicId, username,
                            passwordEncoder.encode(rawPassword), "active"),
                    "manager");
            TestFixtures.seedRolePermissionDefaults(connection, clinicId);
            UUID employeeId = TestFixtures.insertEmployee(connection, clinicId, "منى أحمد");
            UUID employeeUserId = TestFixtures.insertUser(connection, clinicId, "emp-" + uniqueSuffix(),
                    passwordEncoder.encode("employee-pass"), "active");
            UUID employeeMembershipId = TestFixtures.insertMembership(connection, clinicId, employeeUserId, "assistant");
            TestFixtures.linkMembershipToEmployee(connection, employeeMembershipId, employeeId);

            DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
            dsl.update(CLINIC_SETTINGS)
                    .set(CLINIC_SETTINGS.WORKING_WEEKDAYS, new Short[] { 1, 2, 3, 4, 5, 6, 7 })
                    .set(CLINIC_SETTINGS.VOLUME_TARGET, withVolumeTarget ? new java.math.BigDecimal("20000") : null)
                    .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                    .execute();

            UUID taskId = dsl.insertInto(TASK_DEFINITION, TASK_DEFINITION.CLINIC_ID, TASK_DEFINITION.NAME,
                    TASK_DEFINITION.DIMENSION, TASK_DEFINITION.FREQUENCY, TASK_DEFINITION.REQUIRES_PHOTO,
                    TASK_DEFINITION.EMPLOYEE_ID, TASK_DEFINITION.DISPLAY_ORDER)
                    .values(clinicId, "تعقيم الأدوات", TaskDimension.fanni, TaskFrequency.daily, false, employeeId, 1)
                    .returningResult(TASK_DEFINITION.ID)
                    .fetchOne(TASK_DEFINITION.ID);

            LocalDate today = LocalDate.now();
            dsl.insertInto(DAILY_RECORD, DAILY_RECORD.CLINIC_ID, DAILY_RECORD.EMPLOYEE_ID, DAILY_RECORD.WORK_DATE)
                    .values(clinicId, employeeId, today)
                    .execute();
            dsl.insertInto(SELF_CHECK, SELF_CHECK.CLINIC_ID, SELF_CHECK.EMPLOYEE_ID, SELF_CHECK.WORK_DATE,
                    SELF_CHECK.CHECKED_IN_AT, SELF_CHECK.CHECKED_OUT_AT)
                    .values(clinicId, employeeId, today,
                            today.atTime(8, 55).atOffset(OffsetDateTime.now().getOffset()),
                            today.atTime(17, 0).atOffset(OffsetDateTime.now().getOffset()))
                    .execute();
            UUID recordId = dsl.select(DAILY_RECORD.ID)
                    .from(DAILY_RECORD)
                    .where(DAILY_RECORD.CLINIC_ID.eq(clinicId), DAILY_RECORD.EMPLOYEE_ID.eq(employeeId),
                            DAILY_RECORD.WORK_DATE.eq(today))
                    .fetchOne(DAILY_RECORD.ID);
            dsl.insertInto(DAILY_TASK_COMPLETION, DAILY_TASK_COMPLETION.DAILY_RECORD_ID,
                    DAILY_TASK_COMPLETION.TASK_DEFINITION_ID, DAILY_TASK_COMPLETION.DONE,
                    DAILY_TASK_COMPLETION.REVIEW_STATUS)
                    .values(recordId, taskId, true, TaskReviewStatus.approved)
                    .execute();

            return new Seed(slug, username, rawPassword, employeeId);
        }
    }

    @Test
    @DisplayName("Manager views paced volume and team summary, records volume, and drills into an employee")
    void viewsDashboardRecordsVolumeAndDrillsIntoEmployee() throws Exception {
        Seed seed = seedManagerWithScoredEmployee(true);

        page().navigate(getUrl() + "login");
        login(seed.username(), seed.rawPassword(), seed.slug());

        page().navigate(getUrl() + "admin-dashboard/overview");

        PlaywrightAssertions.assertThat(page().getByText("حجم الإنتاج الشهري")).isVisible();
        PlaywrightAssertions.assertThat(page().getByText("المستهدف حتى الآن")).isVisible();
        PlaywrightAssertions.assertThat(page().getByText("منى أحمد")).isVisible();

        page().locator("input[name=amount]").fill("5000");
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("حفظ الرقم")).click();
        PlaywrightAssertions.assertThat(page().locator("#volume-card")).containsText("5000");

        page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("منى أحمد")).click();
        PlaywrightAssertions.assertThat(page()).hasURL(java.util.regex.Pattern.compile(".*/evaluation.*employee=" + seed.employeeId() + ".*"));
        PlaywrightAssertions.assertThat(page().getByText("النتيجة النهائية")).isVisible();

        page().navigate(getUrl() + "admin-dashboard/staff?employee=" + seed.employeeId());
        PlaywrightAssertions.assertThat(page().getByText("تقرير التأهيل (الأكاديمية)")).isVisible();
    }

    @Test
    @DisplayName("No operating target skips the volume component but still shows the team summary")
    void noOperatingTargetSkipsVolumeComponent() throws Exception {
        Seed seed = seedManagerWithScoredEmployee(false);

        page().navigate(getUrl() + "login");
        login(seed.username(), seed.rawPassword(), seed.slug());

        page().navigate(getUrl() + "admin-dashboard/overview");

        PlaywrightAssertions.assertThat(page().getByText("لم يتم تحديد هدف شهري")).isVisible();
        PlaywrightAssertions.assertThat(page().locator(".clinicos-progress-track")).hasCount(0);
        PlaywrightAssertions.assertThat(page().getByText("منى أحمد")).isVisible();
    }

    private record Seed(String slug, String username, String rawPassword, UUID employeeId) {
    }
}
