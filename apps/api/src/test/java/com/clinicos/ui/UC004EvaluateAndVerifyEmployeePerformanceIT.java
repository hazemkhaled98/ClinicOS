package com.clinicos.ui;

import static com.clinicos.shared.jooq.tables.Attachment.ATTACHMENT;
import static com.clinicos.shared.jooq.tables.ClinicSettings.CLINIC_SETTINGS;
import static com.clinicos.shared.jooq.tables.DailyRecord.DAILY_RECORD;
import static com.clinicos.shared.jooq.tables.DailyTaskCompletion.DAILY_TASK_COMPLETION;
import static com.clinicos.shared.jooq.tables.SelfCheck.SELF_CHECK;
import static com.clinicos.shared.jooq.tables.TaskAssignment.TASK_ASSIGNMENT;
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
import com.clinicos.shared.jooq.enums.AssignmentProposer;
import com.clinicos.shared.jooq.enums.AssignmentStatus;
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
import com.clinicos.shared.jooq.enums.TaskReviewStatus;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.microsoft.playwright.options.AriaRole;

/**
 * UC-004: Evaluate and Verify Employee Performance, exercised end-to-end in a
 * real browser against the manager's {@code /evaluation} review screen —
 * browse the monthly scorecard, approve/reject pending daily-task completions,
 * assign a new additional task, and approve a submitted additional task.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UC004EvaluateAndVerifyEmployeePerformanceIT extends AbstractBrowserIT {

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

    private Seed seedManagerWithReviewableWork() throws Exception {
        String slug = "clinic-" + uniqueSuffix();
        String username = "mgr-" + uniqueSuffix();
        String rawPassword = "manager-pass";
        try (Connection connection = DriverManager.getConnection(
                PostgresTestSupport.POSTGRES.getJdbcUrl(),
                PostgresTestSupport.POSTGRES.getUsername(),
                PostgresTestSupport.POSTGRES.getPassword())) {
            LocalDate today = LocalDate.now();
            UUID clinicId = TestFixtures.insertClinic(connection, "Test Clinic " + username, slug);
            TestFixtures.insertMembership(connection, clinicId,
                    TestFixtures.insertUser(connection, clinicId, username,
                            passwordEncoder.encode(rawPassword), "active"),
                    "manager");
            TestFixtures.seedRolePermissionDefaults(connection, clinicId);
            UUID employeeId = TestFixtures.insertEmployee(connection, clinicId, "منى أحمد");

            DSL.using(connection, SQLDialect.POSTGRES)
                    .update(CLINIC_SETTINGS)
                    .set(CLINIC_SETTINGS.WORKING_WEEKDAYS, new Short[] { 1, 2, 3, 4, 5, 6, 7 })
                    .where(CLINIC_SETTINGS.CLINIC_ID.eq(clinicId))
                    .execute();

            UUID approveTaskId = insertTask(connection, clinicId, employeeId, "تعقيم الأدوات", false);
            UUID rejectTaskId = insertTask(connection, clinicId, employeeId, "ترتيب ملفات المرضى", false);
            UUID completedTaskId = insertTask(connection, clinicId, employeeId, "تطهير الأسطح", false);
            UUID photoTaskId = insertTask(connection, clinicId, employeeId, "تعقيم غرفة العمليات", true);

            seedAttendanceOnly(connection, clinicId, employeeId, today);
            seedCompletion(connection, clinicId, employeeId, approveTaskId, today, TaskReviewStatus.pending, null);
            seedCompletion(connection, clinicId, employeeId, rejectTaskId, today, TaskReviewStatus.pending, null);
            seedCompletion(connection, clinicId, employeeId, completedTaskId, today, TaskReviewStatus.approved, null);
            UUID photoId = insertAttachment(connection, clinicId);
            seedCompletion(connection, clinicId, employeeId, photoTaskId, today, TaskReviewStatus.pending, photoId);

            UUID assignmentId = insertSubmittedAssignment(connection, clinicId, employeeId,
                    "حضور ورشة تعقيم الأسنان", null);
            UUID rejectAssignmentId = insertSubmittedAssignment(connection, clinicId, employeeId,
                    "تنظيف مخزن الأدوية", insertAttachment(connection, clinicId));

            return new Seed(slug, username, rawPassword, employeeId, assignmentId, rejectAssignmentId);
        }
    }

    private UUID insertTask(Connection connection, UUID clinicId, UUID employeeId, String name, boolean requiresPhoto)
            throws Exception {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(TASK_DEFINITION, TASK_DEFINITION.CLINIC_ID, TASK_DEFINITION.NAME,
                        TASK_DEFINITION.DIMENSION, TASK_DEFINITION.FREQUENCY, TASK_DEFINITION.REQUIRES_PHOTO,
                        TASK_DEFINITION.EMPLOYEE_ID, TASK_DEFINITION.DISPLAY_ORDER)
                .values(clinicId, name, TaskDimension.fanni, TaskFrequency.daily, requiresPhoto, employeeId, 1)
                .returningResult(TASK_DEFINITION.ID)
                .fetchOne(TASK_DEFINITION.ID);
    }

    private UUID insertAttachment(Connection connection, UUID clinicId) {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(ATTACHMENT, ATTACHMENT.CLINIC_ID, ATTACHMENT.STORAGE_KEY, ATTACHMENT.CONTENT_TYPE,
                        ATTACHMENT.BYTE_SIZE)
                .values(clinicId, "test/" + uniqueSuffix() + ".jpg", "image/jpeg", 1024)
                .returningResult(ATTACHMENT.ID)
                .fetchOne(ATTACHMENT.ID);
    }

    private void seedAttendanceOnly(Connection connection, UUID clinicId, UUID employeeId, LocalDate workDate) {
        DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(DAILY_RECORD, DAILY_RECORD.CLINIC_ID, DAILY_RECORD.EMPLOYEE_ID, DAILY_RECORD.WORK_DATE)
                .values(clinicId, employeeId, workDate)
                .execute();
        DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(SELF_CHECK, SELF_CHECK.CLINIC_ID, SELF_CHECK.EMPLOYEE_ID, SELF_CHECK.WORK_DATE,
                        SELF_CHECK.CHECKED_IN_AT, SELF_CHECK.CHECKED_OUT_AT)
                .values(clinicId, employeeId, workDate,
                        workDate.atTime(8, 55).atOffset(OffsetDateTime.now().getOffset()),
                        workDate.atTime(17, 0).atOffset(OffsetDateTime.now().getOffset()))
                .execute();
    }

    private void seedCompletion(Connection connection, UUID clinicId, UUID employeeId, UUID taskId,
            LocalDate workDate, TaskReviewStatus reviewStatus, UUID photoId) {
        DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
        UUID recordId = dsl.select(DAILY_RECORD.ID)
                .from(DAILY_RECORD)
                .where(DAILY_RECORD.CLINIC_ID.eq(clinicId), DAILY_RECORD.EMPLOYEE_ID.eq(employeeId),
                        DAILY_RECORD.WORK_DATE.eq(workDate))
                .fetchOne(DAILY_RECORD.ID);
        dsl.insertInto(DAILY_TASK_COMPLETION, DAILY_TASK_COMPLETION.DAILY_RECORD_ID,
                DAILY_TASK_COMPLETION.TASK_DEFINITION_ID, DAILY_TASK_COMPLETION.DONE,
                DAILY_TASK_COMPLETION.REVIEW_STATUS, DAILY_TASK_COMPLETION.PHOTO_ID)
                .values(recordId, taskId, true, reviewStatus, photoId)
                .execute();
    }

    private UUID insertSubmittedAssignment(Connection connection, UUID clinicId, UUID employeeId, String name,
            UUID proofPhotoId) throws Exception {
        return DSL.using(connection, SQLDialect.POSTGRES)
                .insertInto(TASK_ASSIGNMENT, TASK_ASSIGNMENT.CLINIC_ID, TASK_ASSIGNMENT.EMPLOYEE_ID,
                        TASK_ASSIGNMENT.NAME, TASK_ASSIGNMENT.PROPOSED_BY, TASK_ASSIGNMENT.DONE_AT,
                        TASK_ASSIGNMENT.PROOF_PHOTO_ID)
                .values(clinicId, employeeId, name, AssignmentProposer.manager, OffsetDateTime.now(), proofPhotoId)
                .returningResult(TASK_ASSIGNMENT.ID)
                .fetchOne(TASK_ASSIGNMENT.ID);
    }

    @Test
    @DisplayName("Manager opens the review screen, approves and rejects completions, and assigns a task")
    void reviewsAndMutatesMonthlyEvaluation() throws Exception {
        Seed seed = seedManagerWithReviewableWork();

        page().navigate(getUrl() + "login");
        login(seed.username(), seed.rawPassword(), seed.slug());

        page().navigate(getUrl() + "evaluation?employee=" + seed.employeeId());

        PlaywrightAssertions.assertThat(page().getByText("النتيجة النهائية")).isVisible();
        PlaywrightAssertions.assertThat(page().getByText("مهام يومية للمراجعة")).isVisible();
        PlaywrightAssertions.assertThat(page().getByText("منى أحمد")).isVisible();

        Locator approveRow = rowContaining("تعقيم الأدوات");
        approveRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("اعتماد")).click();
        PlaywrightAssertions.assertThat(page().locator("#toast-root")).containsText("تم اعتماد الإنجاز");
        PlaywrightAssertions.assertThat(rowContaining("تعقيم الأدوات").getByText("مُعتمد")).isVisible();

        Locator rejectRow = rowContaining("ترتيب ملفات المرضى");
        rejectRow.locator("input[name=reason]").fill("لم يتم توثيق الإنجاز بصورة");
        rejectRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("رفض")).click();
        PlaywrightAssertions.assertThat(page().locator("#toast-root")).containsText("تم رفض الإنجاز");
        PlaywrightAssertions.assertThat(rowContaining("ترتيب ملفات المرضى").getByText("لم يتم توثيق الإنجاز بصورة")).isVisible();

        String newTask = "تنظيف غرفة التعقيم " + uniqueSuffix();
        page().locator("form input[name=name]").fill(newTask);
        page().locator("form input[name=dueDate]").fill("2099-12-31");
        page().getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("تعيين")).click();
        PlaywrightAssertions.assertThat(page().locator("#toast-root")).containsText("تم تعيين المهمة");
        PlaywrightAssertions.assertThat(rowContaining(newTask).getByText("بانتظار الاعتماد")).isVisible();

        Locator submittedRow = rowContaining("حضور ورشة تعقيم الأسنان");
        PlaywrightAssertions.assertThat(submittedRow.getByText("مُسلَّم — بانتظار الاعتماد")).isVisible();
        submittedRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("اعتماد")).click();
        PlaywrightAssertions.assertThat(page().locator("#toast-root")).containsText("تم اعتماد المهمة");
        PlaywrightAssertions.assertThat(rowContaining("حضور ورشة تعقيم الأسنان").getByText("مُعتمد")).isVisible();

        PlaywrightAssertions.assertThat(rowContaining("تعقيم غرفة العمليات").getByText("⬆ صورة الإثبات")).isVisible();

        Locator rejectAssignmentRow = rowContaining("تنظيف مخزن الأدوية");
        PlaywrightAssertions.assertThat(rejectAssignmentRow.getByText("⬆ صورة الإثبات")).isVisible();
        rejectAssignmentRow.locator("input[name=reason]").fill("لم تكتمل المهمة فعلياً");
        rejectAssignmentRow.getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("رفض")).click();
        PlaywrightAssertions.assertThat(page().locator("#toast-root")).containsText("تم رفض المهمة");
        PlaywrightAssertions.assertThat(rowContaining("تنظيف مخزن الأدوية").getByText("مرفوض")).isVisible();
    }

    private Locator rowContaining(String text) {
        return page().locator("tr", new Page.LocatorOptions().setHasText(text));
    }

    private record Seed(String slug, String username, String rawPassword, UUID employeeId,
            UUID assignmentId, UUID rejectAssignmentId) {
    }
}