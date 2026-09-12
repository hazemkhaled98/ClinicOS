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
import java.util.List;
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
import com.clinicos.shared.jooq.enums.StaffRole;
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
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
        UUID taskId = seedTask(clinicA, StaffRole.assistant, "تنظيف العيادة", TaskDimension.fanni, TaskFrequency.daily, false);

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
        UUID taskId = seedTask(clinicA, StaffRole.assistant, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);

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
        UUID taskId = seedTask(clinicA, StaffRole.assistant, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, true);
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
        UUID taskId = seedTask(clinicA, StaffRole.assistant, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
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
        UUID taskId = seedTask(clinicA, StaffRole.assistant, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
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
        UUID taskId = seedTask(clinicA, StaffRole.assistant, "تنظيف", TaskDimension.fanni, TaskFrequency.daily, false);
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
        UUID taskId = seedTask(clinicA, StaffRole.assistant, "تنظيف", TaskDimension.fanni, TaskFrequency.weekly, false);
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

    private UUID seedTask(UUID clinicId, StaffRole role, String name, TaskDimension dimension,
            TaskFrequency frequency, boolean requiresPhoto) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(TASK_DEFINITION)
                    .set(TASK_DEFINITION.CLINIC_ID, clinicId)
                    .set(TASK_DEFINITION.STAFF_ROLE, role)
                    .set(TASK_DEFINITION.NAME, name)
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