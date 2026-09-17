package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.TaskAssignment.TASK_ASSIGNMENT;
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
import com.clinicos.shared.jooq.enums.AssignmentProposer;
import com.clinicos.shared.jooq.enums.AssignmentStatus;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.TaskAssignmentService.Assignment;
import com.clinicos.staff.api.TaskAssignmentService.AssignmentForm;
import com.clinicos.staff.api.TaskAssignmentService.Proposer;

@SpringBootTest(classes = Application.class)
class DefaultTaskAssignmentServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultTaskAssignmentService taskAssignmentService;

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
    void today_returnsUpcomingAndNoDueDateAssignments() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID upcomingId = seedAssignment(clinicA, employeeId, "مهمة الغد", LocalDate.now().plusDays(1), AssignmentStatus.approved);
        seedAssignment(clinicA, employeeId, "مهمة فاتت", LocalDate.now().minusDays(3), AssignmentStatus.approved);
        UUID openId = seedAssignment(clinicA, employeeId, "مهمة بدون موعد", null, AssignmentStatus.approved);

        List<Assignment> assignments = taskAssignmentService.today(clinicA, employeeId);

        assertThat(assignments).extracting(Assignment::id)
                .containsExactlyInAnyOrder(upcomingId, openId);
    }

    @Test
    void markDone_approvedAssignment_setsDoneAt() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID assignmentId = seedAssignment(clinicA, employeeId, "مهمة", LocalDate.now(), AssignmentStatus.approved);

        Assignment done = taskAssignmentService.markDone(clinicA, employeeId, assignmentId, null);

        assertThat(done.doneAt()).isNotNull();
        assertThat(done.status()).isEqualTo("approved");
    }

    @Test
    void markDone_pendingAssignment_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID assignmentId = seedAssignment(clinicA, employeeId, "مهمة", LocalDate.now(), AssignmentStatus.pending);

        assertThatThrownBy(() -> taskAssignmentService.markDone(clinicA, employeeId, assignmentId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المهمة غير معتمدة أو غير موجودة");
    }

    @Test
    void markDone_missingAssignment_throws() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");

        assertThatThrownBy(() -> taskAssignmentService.markDone(clinicA, employeeId, UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المهمة غير معتمدة أو غير موجودة");
    }

    @Test
    void markDone_secondClick_returnsExistingState() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID assignmentId = seedAssignment(clinicA, employeeId, "مهمة", LocalDate.now(), AssignmentStatus.approved);

        Assignment first = taskAssignmentService.markDone(clinicA, employeeId, assignmentId, null);
        Assignment second = taskAssignmentService.markDone(clinicA, employeeId, assignmentId, null);

        assertThat(second.doneAt()).isEqualTo(first.doneAt());
    }

    @Test
    void markDone_crossClinicAssignment_isInvisible() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeA = createEmployee(clinicA, "أحمد");
        UUID assignmentA = seedAssignment(clinicA, employeeA, "مهمة عيادة A", LocalDate.now(), AssignmentStatus.approved);

        TenantContext.set(clinicB);
        UUID employeeB = createEmployee(clinicB, "محمد");
        UUID assignmentB = seedAssignment(clinicB, employeeB, "مهمة عيادة B", LocalDate.now(), AssignmentStatus.approved);

        TenantContext.set(clinicA);
        assertThatThrownBy(() -> taskAssignmentService.markDone(clinicA, employeeA, assignmentB, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المهمة غير معتمدة أو غير موجودة");
        taskAssignmentService.markDone(clinicA, employeeA, assignmentA, null);
    }

    @Test
    void propose_blankName_throws() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");

        assertThatThrownBy(() -> taskAssignmentService.propose(clinicA, employeeId,
                new AssignmentForm(employeeId, "   ", LocalDate.now()), Proposer.SELF))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("اسم المهمة مطلوب");
    }

    @Test
    void propose_insertsPendingAssignment() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");

        Assignment proposed = taskAssignmentService.propose(clinicA, employeeId,
                new AssignmentForm(employeeId, "طلب أدوات", LocalDate.now().plusDays(1)), Proposer.SELF);

        assertThat(proposed.status()).isEqualTo("pending");
        assertThat(proposed.dueDate()).isEqualTo(LocalDate.now().plusDays(1));

        List<Assignment> today = taskAssignmentService.today(clinicA, employeeId);
        assertThat(today).extracting(Assignment::id).contains(proposed.id());
    }

    @Test
    void propose_nullDueDate_defaultsToToday() {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");

        Assignment proposed = taskAssignmentService.propose(clinicA, employeeId,
                new AssignmentForm(employeeId, "طلب أدوات", null), Proposer.MANAGER);

        assertThat(proposed.dueDate()).isEqualTo(LocalDate.now());
        assertThat(proposed.status()).isEqualTo("pending");
    }

    @Test
    void listForMonth_returnsOnlyMonthAssignments() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID currentA = seedAssignment(clinicA, employeeId, "هذا الشهر", LocalDate.now(), AssignmentStatus.approved);
        UUID currentB = seedAssignment(clinicA, employeeId, "هذا الشهر أيضاً", null, AssignmentStatus.pending);
        UUID previous = seedAssignmentAt(clinicA, employeeId, "الشهر الماضي", LocalDate.now(),
                AssignmentStatus.approved, java.time.LocalDate.now().minusMonths(1).withDayOfMonth(15).atTime(9, 0));

        List<Assignment> assignments = taskAssignmentService.listForMonth(clinicA, employeeId, java.time.YearMonth.now());

        assertThat(assignments).extracting(Assignment::id).containsExactlyInAnyOrder(currentA, currentB);
        assertThat(assignments).noneMatch(a -> a.id().equals(previous));
    }

    @Test
    void listForMonth_crossClinicEmployee_returnsEmpty() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeA = createEmployee(clinicA, "أحمد");

        TenantContext.set(clinicB);
        UUID employeeB = createEmployee(clinicB, "محمد");
        seedAssignment(clinicB, employeeB, "مهمة عيادة B", LocalDate.now(), AssignmentStatus.approved);

        TenantContext.set(clinicA);
        List<Assignment> assignments = taskAssignmentService.listForMonth(clinicA, employeeA, java.time.YearMonth.now());
        assertThat(assignments).isEmpty();
    }

    @Test
    void approve_pendingAssignment_setsApproved() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID assignmentId = seedAssignment(clinicA, employeeId, "طلب أدوات", LocalDate.now().plusDays(1), AssignmentStatus.pending);

        Assignment approved = taskAssignmentService.approve(clinicA, assignmentId, insertApproverMembership());

        assertThat(approved.status()).isEqualTo("approved");
        assertThat(approved.approvedAt()).isNotNull();
        assertThat(approved.doneAt()).isNull();
    }

    @Test
    void approve_alreadyReviewed_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID assignmentId = seedAssignment(clinicA, employeeId, "طلب أدوات", LocalDate.now().plusDays(1), AssignmentStatus.approved);

        assertThatThrownBy(() -> taskAssignmentService.approve(clinicA, assignmentId, insertApproverMembership()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ليست بانتظار الاعتماد");
    }

    @Test
    void reject_pendingAssignment_setsRejected() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID assignmentId = seedAssignment(clinicA, employeeId, "طلب أدوات", LocalDate.now().plusDays(1), AssignmentStatus.pending);

        Assignment rejected = taskAssignmentService.reject(clinicA, assignmentId, insertApproverMembership(), "غير مناسب");

        assertThat(rejected.status()).isEqualTo("rejected");
        assertThat(rejected.approvedAt()).isNotNull();
    }

    @Test
    void reject_blankReason_throws() throws Exception {
        TenantContext.set(clinicA);
        UUID employeeId = createEmployee(clinicA, "أحمد");
        UUID assignmentId = seedAssignment(clinicA, employeeId, "طلب أدوات", LocalDate.now().plusDays(1), AssignmentStatus.pending);

        assertThatThrownBy(() -> taskAssignmentService.reject(clinicA, assignmentId, insertApproverMembership(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("سبب الرفض مطلوب");
    }

    private UUID seedAssignmentAt(UUID clinicId, UUID employeeId, String name, LocalDate dueDate,
            AssignmentStatus status, java.time.LocalDateTime assignedAt) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(TASK_ASSIGNMENT)
                    .set(TASK_ASSIGNMENT.CLINIC_ID, clinicId)
                    .set(TASK_ASSIGNMENT.EMPLOYEE_ID, employeeId)
                    .set(TASK_ASSIGNMENT.NAME, name)
                    .set(TASK_ASSIGNMENT.PROPOSED_BY, AssignmentProposer.manager)
                    .set(TASK_ASSIGNMENT.DUE_DATE, dueDate)
                    .set(TASK_ASSIGNMENT.STATUS, status)
                    .set(TASK_ASSIGNMENT.ASSIGNED_AT, assignedAt.atOffset(java.time.OffsetDateTime.now().getOffset()))
                    .returningResult(TASK_ASSIGNMENT.ID)
                    .fetchOne(TASK_ASSIGNMENT.ID);
        }
    }

    private UUID insertApproverMembership() throws Exception {
        try (Connection conn = superuser()) {
            UUID userId = TestFixtures.insertUser(conn, clinicA);
            return TestFixtures.insertMembership(conn, clinicA, userId, "manager");
        }
    }

    private UUID seedAssignment(UUID clinicId, UUID employeeId, String name, LocalDate dueDate,
            AssignmentStatus status) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(TASK_ASSIGNMENT)
                    .set(TASK_ASSIGNMENT.CLINIC_ID, clinicId)
                    .set(TASK_ASSIGNMENT.EMPLOYEE_ID, employeeId)
                    .set(TASK_ASSIGNMENT.NAME, name)
                    .set(TASK_ASSIGNMENT.PROPOSED_BY, AssignmentProposer.manager)
                    .set(TASK_ASSIGNMENT.DUE_DATE, dueDate)
                    .set(TASK_ASSIGNMENT.STATUS, status)
                    .returningResult(TASK_ASSIGNMENT.ID)
                    .fetchOne(TASK_ASSIGNMENT.ID);
        }
    }

    private UUID createEmployee(UUID clinicId, String name) {
        return employeeService.create(clinicId,
                new EmployeeService.EmployeeRequest(name, BigDecimal.ZERO, BigDecimal.ZERO,
                        LocalTime.of(9, 0), LocalTime.of(17, 0), false, null)).id();
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}