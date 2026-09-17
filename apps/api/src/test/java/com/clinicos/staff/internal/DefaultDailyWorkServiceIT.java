package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.DailyRecord.DAILY_RECORD;
import static com.clinicos.shared.jooq.tables.DailyTaskCompletion.DAILY_TASK_COMPLETION;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jooq.InsertSetMoreStep;
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
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
import com.clinicos.shared.jooq.tables.records.TaskDefinitionRecord;
import com.clinicos.staff.api.DailyWorkService;
import com.clinicos.staff.api.DailyWorkService.DailyTask;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.SelfCheckService;

@SpringBootTest(classes = Application.class)
class DefaultDailyWorkServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultDailyWorkService dailyWorkService;

    @Autowired
    private DefaultSelfCheckService selfCheckService;

    @Autowired
    private EmployeeService employeeService;

    private UUID clinicA;
    private UUID clinicB;

    @BeforeEach
    void seedClinics() throws Exception {
        try (Connection conn = superuser()) {
            clinicA = TestFixtures.insertClinic(conn);
            clinicB = TestFixtures.insertClinic(conn);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void today_assistantRole_returnsTaskList() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف العيادة", TaskDimension.fanni, TaskFrequency.daily, false);

        List<DailyTask> tasks = dailyWorkService.today(clinicA, employeeId);

        assertThat(tasks).hasSize(1);
        assertThat(tasks.get(0).taskDefinitionId()).isEqualTo(taskId);
        assertThat(tasks.get(0).name()).isEqualTo("تنظيف العيادة");
        assertThat(tasks.get(0).done()).isFalse();
        assertThat(tasks.get(0).lastCompletedDate()).isNull();
    }

    @Test
    void today_managerRole_returnsEmptyList() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "manager");

        List<DailyTask> tasks = dailyWorkService.today(clinicA, employeeId);

        assertThat(tasks).isEmpty();
    }

    @Test
    void today_noMembership_returnsEmptyList() {
        TenantContext.set(clinicA);
        UUID orphanId = UUID.randomUUID();

        List<DailyTask> tasks = dailyWorkService.today(clinicA, orphanId);

        assertThat(tasks).isEmpty();
    }

    @Test
    void complete_checkInRequired_throwsWhenNotCheckedIn() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);

        assertThatThrownBy(() -> dailyWorkService.complete(clinicA, employeeId, taskId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("لازم تسجّل الحضور الأول");
    }

    @Test
    void complete_taskNotFound_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        selfCheckService.checkIn(clinicA, employeeId);

        assertThatThrownBy(() -> dailyWorkService.complete(clinicA, employeeId, UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المهمة غير موجودة");
    }

    @Test
    void complete_requiresPhotoWithNullPhotoId_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, true);
        selfCheckService.checkIn(clinicA, employeeId);

        assertThatThrownBy(() -> dailyWorkService.complete(clinicA, employeeId, taskId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("لازم ترفق صورة لإثبات هذه المهمة");
    }

    @Test
    void complete_thenToday_doneIsTrue() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, employeeId);

        dailyWorkService.complete(clinicA, employeeId, taskId, null);

        DailyTask task = dailyWorkService.today(clinicA, employeeId).stream()
                .filter(t -> t.taskDefinitionId().equals(taskId)).findFirst().orElseThrow();
        assertThat(task.done()).isTrue();
        assertThat(task.completedAt()).isNotNull();
    }

    @Test
    void complete_idempotent_secondClick_noException() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, employeeId);

        dailyWorkService.complete(clinicA, employeeId, taskId, null);
        dailyWorkService.complete(clinicA, employeeId, taskId, null);

        DailyTask task = dailyWorkService.today(clinicA, employeeId).stream()
                .filter(t -> t.taskDefinitionId().equals(taskId)).findFirst().orElseThrow();
        assertThat(task.done()).isTrue();
    }

    @Test
    void uncomplete_thenToday_doneIsFalse() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, employeeId);

        dailyWorkService.complete(clinicA, employeeId, taskId, null);
        dailyWorkService.uncomplete(clinicA, employeeId, taskId);

        DailyTask task = dailyWorkService.today(clinicA, employeeId).stream()
                .filter(t -> t.taskDefinitionId().equals(taskId)).findFirst().orElseThrow();
        assertThat(task.done()).isFalse();
        assertThat(task.completedAt()).isNull();
    }

    @Test
    void today_lastCompletedDate_fromPreviousDayTask() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.weekly, false);
        selfCheckService.checkIn(clinicA, employeeId);

        UUID dailyRecordIdYday;
        try (Connection conn = superuser()) {
            dailyRecordIdYday = DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(DAILY_RECORD)
                    .set(DAILY_RECORD.ID, UUID.randomUUID())
                    .set(DAILY_RECORD.CLINIC_ID, clinicA)
                    .set(DAILY_RECORD.EMPLOYEE_ID, employeeId)
                    .set(DAILY_RECORD.WORK_DATE, LocalDate.now().minusDays(1))
                    .onConflict(DAILY_RECORD.EMPLOYEE_ID, DAILY_RECORD.WORK_DATE)
                    .doNothing()
                    .returning(DAILY_RECORD.ID)
                    .fetchOne(DAILY_RECORD.ID);
            DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(DAILY_TASK_COMPLETION)
                    .set(DAILY_TASK_COMPLETION.DAILY_RECORD_ID, dailyRecordIdYday)
                    .set(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID, taskId)
                    .set(DAILY_TASK_COMPLETION.DONE, true)
                    .execute();
        }

        List<DailyTask> tasks = dailyWorkService.today(clinicA, employeeId);
        DailyTask task = tasks.stream().filter(t -> t.taskDefinitionId().equals(taskId)).findFirst().orElseThrow();
        assertThat(task.lastCompletedDate()).isEqualTo(java.time.LocalDate.now().minusDays(1));
    }

    @Test
    void today_employeeTargetedTask_visibleOnlyToThatEmployee() throws Exception {
        TenantContext.set(clinicA);
        UUID targetId = createEmployee("مستهدف");
        UUID otherId = createEmployee("آخر");
        linkEmployeeToRole(targetId, "assistant");
        linkEmployeeToRole(otherId, "receptionist");
        UUID taskId = seedTask(clinicA, null, targetId, "تنظيف خاصة", TaskDimension.fanni, TaskFrequency.daily, false);

        List<DailyTask> targetTasks = dailyWorkService.today(clinicA, targetId);
        List<DailyTask> otherTasks = dailyWorkService.today(clinicA, otherId);

        assertThat(targetTasks).extracting(DailyTask::taskDefinitionId).containsExactly(taskId);
        assertThat(otherTasks).isEmpty();
    }

    @Test
    void today_unassignedTask_visibleToAnyRole() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, null, null, "شامل", TaskDimension.fanni, TaskFrequency.daily, false);

        List<DailyTask> tasks = dailyWorkService.today(clinicA, employeeId);

        assertThat(tasks).extracting(DailyTask::taskDefinitionId).containsExactly(taskId);
    }

    @Test
    void complete_employeeTargetedTask_seenByThatEmployee() throws Exception {
        TenantContext.set(clinicA);
        UUID targetId = createEmployee("مستهدف");
        linkEmployeeToRole(targetId, "assistant");
        UUID taskId = seedTask(clinicA, null, targetId, "تنظيف خاصة", TaskDimension.fanni, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, targetId);

        dailyWorkService.complete(clinicA, targetId, taskId, null);

        assertThat(dailyWorkService.today(clinicA, targetId))
                .extracting(DailyTask::taskDefinitionId)
                .containsExactly(taskId);
        assertThat(dailyWorkService.today(clinicA, targetId).get(0).completedAt()).isNotNull();
    }

    @Test
    void complete_taskAssignedToOtherRole_notScopedEmployee_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "receptionist", null, "استقبال", TaskDimension.solooki, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, employeeId);

        assertThatThrownBy(() -> dailyWorkService.complete(clinicA, employeeId, taskId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المهمة غير موجودة");
    }

    @Test
    void uncomplete_taskAssignedToOtherRole_notScopedEmployee_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "receptionist", null, "استقبال", TaskDimension.solooki, TaskFrequency.daily, false);

        assertThatThrownBy(() -> dailyWorkService.uncomplete(clinicA, employeeId, taskId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المهمة غير موجودة");
    }

    @Test
    void today_archivedTask_notReturned() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        archiveTask(taskId);

        List<DailyTask> tasks = dailyWorkService.today(clinicA, employeeId);

        assertThat(tasks).isEmpty();
    }

    @Test
    void complete_archivedTask_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        archiveTask(taskId);
        selfCheckService.checkIn(clinicA, employeeId);

        assertThatThrownBy(() -> dailyWorkService.complete(clinicA, employeeId, taskId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المهمة غير موجودة");
    }

    @Test
    void forDate_returnsReviewState() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, employeeId);
        dailyWorkService.complete(clinicA, employeeId, taskId, null);
        UUID dailyRecordId = todayRecordId(employeeId);

        dailyWorkService.approveReview(clinicA, dailyRecordId, taskId, insertManagerMembership());

        DailyTask task = dailyWorkService.forDate(clinicA, employeeId, LocalDate.now()).stream()
                .filter(t -> t.taskDefinitionId().equals(taskId)).findFirst().orElseThrow();
        assertThat(task.done()).isTrue();
        assertThat(task.reviewStatus()).isEqualTo("approved");
        assertThat(task.reviewReason()).isNull();
        assertThat(task.reviewedAt()).isNotNull();
    }

    @Test
    void rejectReview_setsReason() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, employeeId);
        dailyWorkService.complete(clinicA, employeeId, taskId, null);
        UUID dailyRecordId = todayRecordId(employeeId);

        dailyWorkService.rejectReview(clinicA, dailyRecordId, taskId, insertManagerMembership(), "صوره غير واضحة");

        DailyTask task = dailyWorkService.forDate(clinicA, employeeId, LocalDate.now()).stream()
                .filter(t -> t.taskDefinitionId().equals(taskId)).findFirst().orElseThrow();
        assertThat(task.reviewStatus()).isEqualTo("rejected");
        assertThat(task.reviewReason()).isEqualTo("صوره غير واضحة");
        assertThat(task.reviewedAt()).isNotNull();
    }

    @Test
    void approveReview_notDone_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        dailyWorkService.forDate(clinicA, employeeId, LocalDate.now());
        UUID dailyRecordId = todayRecordId(employeeId);

        assertThatThrownBy(() -> dailyWorkService.approveReview(clinicA, dailyRecordId, taskId, insertManagerMembership()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("غير مكتملة");
    }

    @Test
    void rejectReview_blankReason_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, employeeId);
        dailyWorkService.complete(clinicA, employeeId, taskId, null);
        UUID dailyRecordId = todayRecordId(employeeId);

        assertThatThrownBy(() -> dailyWorkService.rejectReview(clinicA, dailyRecordId, taskId, insertManagerMembership(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("سبب الرفض مطلوب");
    }

    @Test
    void completeAfterApprove_resetsReviewToPending() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee("أحمد");
        linkEmployeeToRole(employeeId, "assistant");
        UUID taskId = seedTask(clinicA, "assistant", null, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
        selfCheckService.checkIn(clinicA, employeeId);
        dailyWorkService.complete(clinicA, employeeId, taskId, null);
        UUID dailyRecordId = todayRecordId(employeeId);
        dailyWorkService.approveReview(clinicA, dailyRecordId, taskId, insertManagerMembership());

        dailyWorkService.complete(clinicA, employeeId, taskId, null);

        DailyTask task = dailyWorkService.forDate(clinicA, employeeId, LocalDate.now()).stream()
                .filter(t -> t.taskDefinitionId().equals(taskId)).findFirst().orElseThrow();
        assertThat(task.done()).isTrue();
        assertThat(task.reviewStatus()).isEqualTo("pending");
        assertThat(task.reviewReason()).isNull();
    }

    private UUID todayRecordId(UUID employeeId) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .select(DAILY_RECORD.ID)
                    .from(DAILY_RECORD)
                    .where(DAILY_RECORD.CLINIC_ID.eq(clinicA))
                    .and(DAILY_RECORD.EMPLOYEE_ID.eq(employeeId))
                    .and(DAILY_RECORD.WORK_DATE.eq(LocalDate.now()))
                    .fetchOne(DAILY_RECORD.ID);
        }
    }

    private UUID insertManagerMembership() throws Exception {
        try (Connection conn = superuser()) {
            UUID userId = TestFixtures.insertUser(conn, clinicA);
            return TestFixtures.insertMembership(conn, clinicA, userId, "manager");
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

    private UUID createEmployee(String name) {
        return employeeService.create(clinicA,
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

    private UUID seedTask(UUID clinicId, String roleCode, UUID employeeId, String name, TaskDimension dimension,
            TaskFrequency frequency, boolean requiresPhoto) throws Exception {
        try (Connection conn = superuser()) {
            InsertSetMoreStep<TaskDefinitionRecord> insert = DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(TASK_DEFINITION)
                    .set(TASK_DEFINITION.CLINIC_ID, clinicId);
            if (roleCode != null) {
                insert = insert.set(TASK_DEFINITION.ROLE_CODE, roleCode);
            }
            if (employeeId != null) {
                insert = insert.set(TASK_DEFINITION.EMPLOYEE_ID, employeeId);
            }
            return insert.set(TASK_DEFINITION.NAME, name)
                    .set(TASK_DEFINITION.DIMENSION, dimension)
                    .set(TASK_DEFINITION.FREQUENCY, frequency)
                    .set(TASK_DEFINITION.REQUIRES_PHOTO, requiresPhoto)
                    .set(TASK_DEFINITION.DISPLAY_ORDER, 1)
                    .returningResult(TASK_DEFINITION.ID)
                    .fetchOne(TASK_DEFINITION.ID);
        }
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}