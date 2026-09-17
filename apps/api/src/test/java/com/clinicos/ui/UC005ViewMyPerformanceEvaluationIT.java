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
 * UC-005: View My Performance Evaluation — an employee opens their own
 * {@code تقييمي} screen and sees only their own monthly figures.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC005ViewMyPerformanceEvaluationIT extends AbstractBrowserIT {

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

    private Seed seedAssistantWithApprovedWork() throws Exception {
        return seedAssistantWithApprovedWork(LocalDate.now());
    }

    private Seed seedAssistantWithApprovedWork(LocalDate workDate) throws Exception {
        String slug = "clinic-" + uniqueSuffix();
        String username = "asst-" + uniqueSuffix();
        String rawPassword = "assistant-pass";
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic " + username, slug);
            UUID employeeId = TestFixtures.insertEmployee(connection, clinicId, "سارة محمد");
            UUID userId = TestFixtures.insertUser(connection, clinicId, username,
                    passwordEncoder.encode(rawPassword), "active");
            UUID membershipId = TestFixtures.insertMembership(connection, clinicId, userId, "assistant");
            TestFixtures.linkMembershipToEmployee(connection, membershipId, employeeId);
            TestFixtures.seedRolePermissionDefaults(connection, clinicId);

            DSL.using(connection, SQLDialect.POSTGRES)
                    .update(CLINIC_SETTINGS)
                    .set(CLINIC_SETTINGS.WORKING_WEEKDAYS, new Short[] { 1, 2, 3, 4, 5, 6, 7 })
                    .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                    .execute();

            UUID taskId = insertTask(connection, clinicId, employeeId, "تعقيم الأدوات");
            seedAttendanceAndCompletion(connection, clinicId, employeeId, taskId, workDate);

            return new Seed(slug, username, rawPassword);
        }
    }

    private UUID insertTask(Connection connection, UUID clinicId, UUID employeeId, String name) {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(TASK_DEFINITION, TASK_DEFINITION.CLINIC_ID, TASK_DEFINITION.NAME,
                        TASK_DEFINITION.DIMENSION, TASK_DEFINITION.FREQUENCY, TASK_DEFINITION.REQUIRES_PHOTO,
                        TASK_DEFINITION.EMPLOYEE_ID, TASK_DEFINITION.DISPLAY_ORDER)
                .values(clinicId, name, TaskDimension.fanni, TaskFrequency.daily, false, employeeId, 1)
                .returningResult(TASK_DEFINITION.ID)
                .fetchOne(TASK_DEFINITION.ID);
    }

    private void seedAttendanceAndCompletion(Connection connection, UUID clinicId, UUID employeeId, UUID taskId,
            LocalDate workDate) {
        DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
        dsl.insertInto(DAILY_RECORD, DAILY_RECORD.CLINIC_ID, DAILY_RECORD.EMPLOYEE_ID, DAILY_RECORD.WORK_DATE)
                .values(clinicId, employeeId, workDate)
                .execute();
        dsl.insertInto(SELF_CHECK, SELF_CHECK.CLINIC_ID, SELF_CHECK.EMPLOYEE_ID, SELF_CHECK.WORK_DATE,
                        SELF_CHECK.CHECKED_IN_AT, SELF_CHECK.CHECKED_OUT_AT)
                .values(clinicId, employeeId, workDate,
                        workDate.atTime(8, 55).atOffset(OffsetDateTime.now().getOffset()),
                        workDate.atTime(17, 0).atOffset(OffsetDateTime.now().getOffset()))
                .execute();
        UUID recordId = dsl.select(DAILY_RECORD.ID)
                .from(DAILY_RECORD)
                .where(DAILY_RECORD.CLINIC_ID.eq(clinicId), DAILY_RECORD.EMPLOYEE_ID.eq(employeeId),
                        DAILY_RECORD.WORK_DATE.eq(workDate))
                .fetchOne(DAILY_RECORD.ID);
        dsl.insertInto(DAILY_TASK_COMPLETION, DAILY_TASK_COMPLETION.DAILY_RECORD_ID,
                        DAILY_TASK_COMPLETION.TASK_DEFINITION_ID, DAILY_TASK_COMPLETION.DONE,
                        DAILY_TASK_COMPLETION.REVIEW_STATUS)
                .values(recordId, taskId, true, TaskReviewStatus.approved)
                .execute();
    }

    @Test
    @DisplayName("Employee opens their own evaluation screen and sees only their own figures")
    void viewsOwnEvaluation() throws Exception {
        Seed seed = seedAssistantWithApprovedWork();

        page().navigate(getUrl() + "login");
        login(seed.username(), seed.rawPassword(), seed.slug());

        page().navigate(getUrl() + "my-evaluation");

        PlaywrightAssertions.assertThat(page().getByText("سارة محمد")).isVisible();
        PlaywrightAssertions.assertThat(page().getByText("النتيجة النهائية")).isVisible();

        page().getByRole(AriaRole.LINK, new Page.GetByRoleOptions().setName("الشهر السابق")).click();
        PlaywrightAssertions.assertThat(page().getByText("لا توجد بيانات كافية لهذا الشهر بعد")).isVisible();
    }

    @Test
    @DisplayName("Employee viewing a past month sees the frozen snapshot")
    void viewsFrozenPastMonth() throws Exception {
        LocalDate lastMonthDay = LocalDate.now().minusMonths(1).withDayOfMonth(1);
        Seed seed = seedAssistantWithApprovedWork(lastMonthDay);

        page().navigate(getUrl() + "login");
        login(seed.username(), seed.rawPassword(), seed.slug());

        page().navigate(getUrl() + "my-evaluation?month=" + lastMonthDay.getYear() + "-"
                + String.format("%02d", lastMonthDay.getMonthValue()));

        PlaywrightAssertions.assertThat(page().getByText("مجمّد (شهر مقفل)")).isVisible();
    }

    private record Seed(String slug, String username, String rawPassword) {
    }
}
