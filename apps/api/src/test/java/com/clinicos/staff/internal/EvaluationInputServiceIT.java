package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.DailyRecord.DAILY_RECORD;
import static com.clinicos.shared.jooq.tables.DailyTaskCompletion.DAILY_TASK_COMPLETION;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.SelfCheck.SELF_CHECK;
import static com.clinicos.shared.jooq.tables.TaskAssignment.TASK_ASSIGNMENT;
import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.UUID;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.shared.TenantContext;
import com.clinicos.shared.jooq.enums.AssignmentProposer;
import com.clinicos.shared.jooq.enums.AssignmentStatus;
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
import com.clinicos.shared.jooq.enums.TaskReviewStatus;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EvaluationInputService.Completion;
import com.clinicos.staff.api.EvaluationInputService.MonthData;
import com.clinicos.staff.api.EvaluationInputService.TaskDef;

@SpringBootTest(classes = Application.class)
class EvaluationInputServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultEvaluationInputService inputService;

    @Autowired
    private EmployeeService employeeService;

    private UUID clinicA;
    private UUID clinicB;
    private UUID employeeA;
    private final YearMonth month = YearMonth.now();

    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = superuser()) {
            clinicA = TestFixtures.insertClinic(conn);
            clinicB = TestFixtures.insertClinic(conn);
        }
        TenantContext.set(clinicA);
        employeeA = createEmployee(clinicA, "أحمد");
        linkEmployeeToRole(employeeA, "assistant");
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void forMonth_fieldsScopedToEmployeeAndMonth() throws Exception {
        TenantContext.set(clinicA);
        UUID tRole = seedTask(clinicA, "assistant", null, "تنظيف", false);
        UUID tAll = seedTask(clinicA, null, null, "شامل", false);
        UUID tArchived = seedTask(clinicA, "assistant", null, "مؤرشف", false);
        archiveTask(tArchived);
        UUID tFuture = seedFutureTask(clinicA, "مستقبلي");

        UUID day0 = seedRecord(employeeA, month.atDay(1));
        seedCompletion(day0, tRole, true, TaskReviewStatus.approved);
        seedCompletion(day0, tAll, true, TaskReviewStatus.approved);
        UUID day1 = seedRecord(employeeA, month.atDay(2));
        seedCompletion(day1, tRole, true, TaskReviewStatus.pending);
        seedRecord(employeeA, month.atDay(3));
        seedAttendance(month.atDay(1));
        seedAttendance(month.atDay(3));
        UUID assignmentA = seedAssignment("مهمة الشهر", null, AssignmentStatus.approved, null);
        seedAssignment("مهمة سابقة", null, AssignmentStatus.approved,
                month.minusMonths(1).atDay(15).atTime(9, 0));

        MonthData data = inputService.forMonth(clinicA, employeeA, month);

        assertThat(data.tasks()).extracting(TaskDef::id)
                .containsExactlyInAnyOrder(tRole, tAll);
        assertThat(data.tasks()).noneMatch(t -> t.id().equals(tArchived) || t.id().equals(tFuture));
        assertThat(data.completions()).containsExactlyInAnyOrder(
                new Completion(tRole, month.atDay(1)),
                new Completion(tAll, month.atDay(1)));
        assertThat(data.loggedDates()).containsExactly(month.atDay(1), month.atDay(2), month.atDay(3));
        assertThat(data.attendance()).hasSize(2);
        assertThat(data.assignments()).extracting(a -> a.id()).containsExactly(assignmentA);
    }

    @Test
    void forMonth_nonMember_returnsEmpty() {
        TenantContext.set(clinicA);
        UUID orphan = UUID.randomUUID();

        MonthData data = inputService.forMonth(clinicA, orphan, month);

        assertThat(data.tasks()).isEmpty();
        assertThat(data.completions()).isEmpty();
        assertThat(data.loggedDates()).isEmpty();
        assertThat(data.attendance()).isEmpty();
        assertThat(data.assignments()).isEmpty();
    }

    @Test
    void forMonth_crossClinicRowsHidden() throws Exception {
        TenantContext.set(clinicB);
        UUID employeeB = createEmployee(clinicB, "محمد");
        UUID taskB;
        try (Connection conn = superuser()) {
            UUID userB = TestFixtures.insertUser(conn, clinicB);
            UUID membershipB = TestFixtures.insertMembership(conn, clinicB, userB, "assistant");
            DSL.using(conn, SQLDialect.POSTGRES)
                    .update(MEMBERSHIP)
                    .set(MEMBERSHIP.EMPLOYEE_ID, employeeB)
                    .where(MEMBERSHIP.ID.eq(membershipB))
                    .execute();
            taskB = seedTask(clinicB, "assistant", null, "تنظيف B", false);
            UUID recordB = seedRecord(conn, clinicB, employeeB, month.atDay(1));
            seedCompletion(conn, clinicB, recordB, taskB, true, TaskReviewStatus.approved);
        }

        TenantContext.set(clinicA);
        MonthData data = inputService.forMonth(clinicA, employeeB, month);

        assertThat(data.tasks()).isEmpty();
        assertThat(data.completions()).isEmpty();
        assertThat(data.loggedDates()).isEmpty();
    }

    private UUID seedRecord(UUID employeeId, LocalDate workDate) throws Exception {
        try (Connection conn = superuser()) {
            return seedRecord(conn, clinicA, employeeId, workDate);
        }
    }

    private UUID seedRecord(Connection conn, UUID clinicId, UUID employeeId, LocalDate workDate) {
        return DSL.using(conn, SQLDialect.POSTGRES)
                .insertInto(DAILY_RECORD)
                .set(DAILY_RECORD.ID, UUID.randomUUID())
                .set(DAILY_RECORD.CLINIC_ID, clinicId)
                .set(DAILY_RECORD.EMPLOYEE_ID, employeeId)
                .set(DAILY_RECORD.WORK_DATE, workDate)
                .returningResult(DAILY_RECORD.ID)
                .fetchOne(DAILY_RECORD.ID);
    }

    private void seedCompletion(UUID recordId, UUID taskId, boolean done, TaskReviewStatus reviewStatus) throws Exception {
        try (Connection conn = superuser()) {
            seedCompletion(conn, clinicA, recordId, taskId, done, reviewStatus);
        }
    }

    private void seedCompletion(Connection conn, UUID clinicId, UUID recordId, UUID taskId, boolean done,
            TaskReviewStatus reviewStatus) {
        DSL.using(conn, SQLDialect.POSTGRES)
                .insertInto(DAILY_TASK_COMPLETION)
                .set(DAILY_TASK_COMPLETION.DAILY_RECORD_ID, recordId)
                .set(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID, taskId)
                .set(DAILY_TASK_COMPLETION.DONE, done)
                .set(DAILY_TASK_COMPLETION.REVIEW_STATUS, reviewStatus)
                .execute();
    }

    private void seedAttendance(LocalDate workDate) throws Exception {
        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(SELF_CHECK, SELF_CHECK.CLINIC_ID, SELF_CHECK.EMPLOYEE_ID,
                            SELF_CHECK.WORK_DATE, SELF_CHECK.CHECKED_IN_AT)
                    .values(clinicA, employeeA, workDate, workDate.atTime(8, 55).atOffset(OffsetDateTime.now().getOffset()))
                    .execute();
        }
    }

    private UUID seedAssignment(String name, LocalDate dueDate, AssignmentStatus status,
            java.time.LocalDateTime assignedAt) throws Exception {
        try (Connection conn = superuser()) {
            org.jooq.InsertSetMoreStep<com.clinicos.shared.jooq.tables.records.TaskAssignmentRecord> insert =
                    DSL.using(conn, SQLDialect.POSTGRES)
                            .insertInto(TASK_ASSIGNMENT)
                            .set(TASK_ASSIGNMENT.CLINIC_ID, clinicA)
                            .set(TASK_ASSIGNMENT.EMPLOYEE_ID, employeeA)
                            .set(TASK_ASSIGNMENT.NAME, name)
                            .set(TASK_ASSIGNMENT.PROPOSED_BY, AssignmentProposer.manager)
                            .set(TASK_ASSIGNMENT.STATUS, status);
            if (dueDate != null) {
                insert = insert.set(TASK_ASSIGNMENT.DUE_DATE, dueDate);
            }
            if (assignedAt != null) {
                insert = insert.set(TASK_ASSIGNMENT.ASSIGNED_AT, assignedAt.atOffset(OffsetDateTime.now().getOffset()));
            }
            return insert.returningResult(TASK_ASSIGNMENT.ID).fetchOne(TASK_ASSIGNMENT.ID);
        }
    }

    private UUID seedTask(UUID clinicId, String roleCode, UUID employeeId, String name, boolean requiresPhoto)
            throws Exception {
        try (Connection conn = superuser()) {
            org.jooq.InsertSetMoreStep<com.clinicos.shared.jooq.tables.records.TaskDefinitionRecord> insert =
                    DSL.using(conn, SQLDialect.POSTGRES)
                            .insertInto(TASK_DEFINITION)
                            .set(TASK_DEFINITION.CLINIC_ID, clinicId)
                            .set(TASK_DEFINITION.NAME, name)
                            .set(TASK_DEFINITION.DIMENSION, TaskDimension.fanni)
                            .set(TASK_DEFINITION.FREQUENCY, TaskFrequency.daily)
                            .set(TASK_DEFINITION.REQUIRES_PHOTO, requiresPhoto)
                            .set(TASK_DEFINITION.DISPLAY_ORDER, 1);
            if (roleCode != null) {
                insert = insert.set(TASK_DEFINITION.ROLE_CODE, roleCode);
            }
            if (employeeId != null) {
                insert = insert.set(TASK_DEFINITION.EMPLOYEE_ID, employeeId);
            }
            return insert.returningResult(TASK_DEFINITION.ID).fetchOne(TASK_DEFINITION.ID);
        }
    }

    private UUID seedFutureTask(UUID clinicId, String name) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(TASK_DEFINITION)
                    .set(TASK_DEFINITION.CLINIC_ID, clinicId)
                    .set(TASK_DEFINITION.ROLE_CODE, "assistant")
                    .set(TASK_DEFINITION.NAME, name)
                    .set(TASK_DEFINITION.DIMENSION, TaskDimension.fanni)
                    .set(TASK_DEFINITION.FREQUENCY, TaskFrequency.daily)
                    .set(TASK_DEFINITION.REQUIRES_PHOTO, false)
                    .set(TASK_DEFINITION.DISPLAY_ORDER, 1)
                    .set(TASK_DEFINITION.CREATED_AT, month.plusMonths(1).atDay(1).atStartOfDay().atOffset(OffsetDateTime.now().getOffset()))
                    .returningResult(TASK_DEFINITION.ID)
                    .fetchOne(TASK_DEFINITION.ID);
        }
    }

    private void archiveTask(UUID taskId) throws Exception {
        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .update(TASK_DEFINITION)
                    .set(TASK_DEFINITION.ARCHIVED_AT, OffsetDateTime.now())
                    .where(TASK_DEFINITION.ID.eq(taskId))
                    .execute();
        }
    }

    private UUID createEmployee(UUID clinicId, String name) {
        return employeeService.create(clinicId,
                new EmployeeService.EmployeeRequest(name, BigDecimal.ZERO, BigDecimal.ZERO,
                        LocalTime.of(9, 0), LocalTime.of(17, 0), false, null)).id();
    }

    private void linkEmployeeToRole(UUID employeeId, String roleCode) throws Exception {
        try (Connection conn = superuser()) {
            UUID userId = TestFixtures.insertUser(conn, clinicA, "user-" + UUID.randomUUID(), "hash", "active", "Test");
            UUID membershipId = TestFixtures.insertMembership(conn, clinicA, userId, roleCode);
            DSL.using(conn, SQLDialect.POSTGRES)
                    .update(MEMBERSHIP)
                    .set(MEMBERSHIP.EMPLOYEE_ID, employeeId)
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .execute();
        }
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}