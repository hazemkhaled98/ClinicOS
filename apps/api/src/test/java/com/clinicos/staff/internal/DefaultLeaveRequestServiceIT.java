package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.EvaluationSnapshot.EVALUATION_SNAPSHOT;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
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
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.LeaveRequestService;
import com.clinicos.staff.api.LeaveRequestService.LeaveRequest;
import com.clinicos.staff.api.LeaveRequestService.LeaveStatus;

/**
 * Behavioural assertions go through the transactional, tenant-bound services, so
 * the RLS policies on {@code leave_request} are part of what is being tested. The
 * superuser connection appears only in fixture seeding, before any tenant is
 * bound; a raw read there would otherwise be filtered by RLS and silently empty.
 */
@SpringBootTest(classes = Application.class)
class DefaultLeaveRequestServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultLeaveRequestService leaveRequests;

    @Autowired
    private EmployeeService employeeService;

    @Autowired
    private WorkCalendarService calendar;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private ActivityLogService activityLogService;

    @Autowired
    private DSLContext dsl;

    private UUID clinicA;
    private UUID clinicB;
    private UUID ownerMembership;
    private UUID managerMembership;
    private UUID staffEmployeeId;
    private UUID staffMembership;
    private UUID otherEmployeeId;
    private UUID otherMembership;
    private UUID managerEmployeeId;

    @BeforeEach
    void seed() throws Exception {
        try (Connection conn = superuser()) {
            clinicA = TestFixtures.insertClinic(conn);
            clinicB = TestFixtures.insertClinic(conn);
            ownerMembership = TestFixtures.insertMembership(conn, clinicA, "owner");
            managerMembership = TestFixtures.insertMembership(conn, clinicA, "manager");
            staffEmployeeId = TestFixtures.insertEmployee(conn, clinicA, "سارة");
            staffMembership = employeeMembership(conn, staffEmployeeId, "assistant");
            otherEmployeeId = TestFixtures.insertEmployee(conn, clinicA, "خالد");
            otherMembership = employeeMembership(conn, otherEmployeeId, "receptionist");
            managerEmployeeId = TestFixtures.insertEmployee(conn, clinicA, "مدير الموظف");
            TestFixtures.linkMembershipToEmployee(conn, managerMembership, managerEmployeeId);
        }
    }

    private UUID employeeMembership(Connection conn, UUID employeeId, String roleCode) throws Exception {
        UUID userId = TestFixtures.insertUser(conn, clinicA);
        UUID membershipId = TestFixtures.insertMembership(conn, clinicA, userId, roleCode);
        TestFixtures.linkMembershipToEmployee(conn, membershipId, employeeId);
        return membershipId;
    }

    @AfterEach
    void clearTenant() throws Exception {
        TenantContext.clear();
    }

    @Test
    void migration_enablesRowLevelSecurityOnLeaveRequest() throws Exception {
        Boolean rowSecurity = dsl.select(DSL.field("relrowsecurity", Boolean.class))
                .from(DSL.table(DSL.name("pg_class")))
                .where(DSL.field("relname", String.class).eq("leave_request"))
                .fetchOne(DSL.field("relrowsecurity", Boolean.class));

        assertThat(rowSecurity).isTrue();
    }

    @Test
    void hasOtherActiveOwnerExcludesTheActor() throws Exception {
        TenantContext.set(clinicA);

        assertThat(leaveRequests.hasOtherActiveOwner(clinicA, ownerMembership)).isFalse();
        try (Connection conn = superuser()) {
            TestFixtures.insertMembership(conn, clinicA, "owner");
        }
        assertThat(leaveRequests.hasOtherActiveOwner(clinicA, ownerMembership)).isTrue();
    }

    @Test
    void submit_storesPendingRequestAndNotifiesApprovers() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LocalDate end = start.plusDays(2);

        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, end, "إجازة سنوية",
                staffMembership);

        assertThat(created.status()).isEqualTo(LeaveStatus.pending);
        assertThat(created.start()).isEqualTo(start);
        assertThat(created.end()).isEqualTo(end);
        assertThat(created.decidedByMembershipId()).isNull();
        assertThat(historyOf(created.id())).extracting(LeaveRequest::status).containsExactly(LeaveStatus.pending);
        assertThat(kindsFor(managerMembership)).contains(NotificationKind.LEAVE_REQUESTED);
        assertThat(kindsFor(ownerMembership)).contains(NotificationKind.LEAVE_REQUESTED);
        assertThat(kindsFor(staffMembership)).doesNotContain(NotificationKind.LEAVE_REQUESTED);
        assertThat(kindsFor(otherMembership)).doesNotContain(NotificationKind.LEAVE_REQUESTED);
        assertThat(notifications.recent(clinicA, managerMembership, 50).get(0).payload())
                .containsEntry("employee", "سارة").containsKey("range");
        assertThat(activityLogService.forDay(clinicA, LocalDate.now(), "leave.submit"))
                .extracting(ActivityLogService.Entry::action).contains("leave.submit");
    }

    @Test
    void submit_rejectsBlankReasonAndInvertedRange() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);

        assertThatThrownBy(() -> leaveRequests.submit(clinicA, staffEmployeeId, start, start, "  ", staffMembership))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> leaveRequests.submit(clinicA, staffEmployeeId, start, start.minusDays(1), "سبب",
                staffMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submit_rejectsActingForAnotherEmployee() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);

        assertThatThrownBy(() -> leaveRequests.submit(clinicA, staffEmployeeId, start, start, "سبب", otherMembership))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(leaveRequests.listForEmployee(clinicA, staffEmployeeId)).isEmpty();
    }

    @Test
    void submit_rejectsOverlappingPendingRange() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(3), "أول", staffMembership);

        assertThatThrownBy(() -> leaveRequests.submit(clinicA, staffEmployeeId, start.plusDays(2), start.plusDays(5),
                "ثان", staffMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submit_rejectsOverlapWithApprovedRange() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest approved = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(2), "أولى",
                staffMembership);
        leaveRequests.approve(clinicA, approved.id(), managerMembership);

        assertThatThrownBy(() -> leaveRequests.submit(clinicA, staffEmployeeId, start.plusDays(1), start.plusDays(3),
                "ثانية", staffMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approve_byManager_marksApprovedAndNotifiesRequester() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(1), "إجازة",
                staffMembership);

        LeaveRequest approved = leaveRequests.approve(clinicA, created.id(), managerMembership);

        assertThat(approved.status()).isEqualTo(LeaveStatus.approved);
        assertThat(approved.decidedByMembershipId()).isEqualTo(managerMembership);
        assertThat(kindsFor(staffMembership)).contains(NotificationKind.LEAVE_APPROVED);
        assertThat(notifications.recent(clinicA, staffMembership, 50).get(0).payload())
                .containsEntry("employee", "سارة").containsKey("range");
        assertThat(activityLogService.forDay(clinicA, LocalDate.now(), "leave.approve"))
                .extracting(ActivityLogService.Entry::action).contains("leave.approve");
    }

    @Test
    void approve_rejectedForNonApproverRole() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(1), "إجازة",
                staffMembership);

        assertThatThrownBy(() -> leaveRequests.approve(clinicA, created.id(), otherMembership))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(historyOf(created.id())).extracting(LeaveRequest::status).containsExactly(LeaveStatus.pending);
    }

    @Test
    void approve_rejectedForSelfApproval() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, managerEmployeeId, start, start.plusDays(1), "إجازة",
                managerMembership);

        assertThatThrownBy(() -> leaveRequests.approve(clinicA, created.id(), managerMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void manager_cannotDecideManagerRequests() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        UUID secondManager = insertEmployeeMembership("مدير ثانٍ", "manager");
        UUID secondManagerEmployeeId = employeeIdOf(secondManager);
        LeaveRequest created = leaveRequests.submit(clinicA, secondManagerEmployeeId, start, start.plusDays(1),
                "إجازة", secondManager);

        assertThatThrownBy(() -> leaveRequests.approve(clinicA, created.id(), managerMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void owner_decidesAnyOtherMembersRequest() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, otherEmployeeId, start, start.plusDays(1), "إجازة",
                otherMembership);

        assertThat(leaveRequests.approve(clinicA, created.id(), ownerMembership).status())
                .isEqualTo(LeaveStatus.approved);
    }

    @Test
    void owner_decisionRejectedForOwnRequest() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        UUID peerOwner;
        try (Connection conn = superuser()) {
            peerOwner = TestFixtures.insertMembership(conn, clinicA, "owner");
        }
        UUID ownerEmployeeId = employeeService
                .create(clinicA, new EmployeeService.EmployeeRequest("المالك", BigDecimal.ZERO, BigDecimal.ZERO,
                        LocalTime.of(9, 0), LocalTime.of(17, 0), false, null))
                .id();
        linkEmployeeToMembership(ownerMembership, ownerEmployeeId);
        LeaveRequest created = leaveRequests.submit(clinicA, ownerEmployeeId, start, start.plusDays(1), "إجازة",
                ownerMembership);

        assertThat(kindsFor(peerOwner)).contains(NotificationKind.LEAVE_REQUESTED);
        assertThat(kindsFor(managerMembership)).doesNotContain(NotificationKind.LEAVE_REQUESTED);
        assertThatThrownBy(() -> leaveRequests.approve(clinicA, created.id(), ownerMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void soleOwnerCannotSubmitLeaveRequest() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        UUID ownerEmployeeId = employeeService
                .create(clinicA, new EmployeeService.EmployeeRequest("المالك", BigDecimal.ZERO, BigDecimal.ZERO,
                        LocalTime.of(9, 0), LocalTime.of(17, 0), false, null))
                .id();
        linkEmployeeToMembership(ownerMembership, ownerEmployeeId);

        assertThatThrownBy(() -> leaveRequests.submit(clinicA, ownerEmployeeId, start, start.plusDays(1), "إجازة",
                ownerMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void secondApproverLosesTheRace() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(1), "إجازة",
                staffMembership);
        leaveRequests.approve(clinicA, created.id(), managerMembership);

        assertThatThrownBy(() -> leaveRequests.approve(clinicA, created.id(), ownerMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reject_requiresNoteAndNotifiesRequester() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(1), "إجازة",
                staffMembership);

        assertThatThrownBy(() -> leaveRequests.reject(clinicA, created.id(), " ", managerMembership))
                .isInstanceOf(IllegalArgumentException.class);

        LeaveRequest rejected = leaveRequests.reject(clinicA, created.id(), "الفترة مزدحمة", managerMembership);

        assertThat(rejected.status()).isEqualTo(LeaveStatus.rejected);
        assertThat(rejected.decisionNote()).isEqualTo("الفترة مزدحمة");
        assertThat(kindsFor(staffMembership)).contains(NotificationKind.LEAVE_REJECTED);
        assertThat(notifications.recent(clinicA, staffMembership, 50).get(0).payload())
                .containsEntry("reason", "الفترة مزدحمة").containsKey("range");
        assertThat(activityLogService.forDay(clinicA, LocalDate.now(), "leave.reject"))
                .extracting(ActivityLogService.Entry::action).contains("leave.reject");
    }

    @Test
    void approve_rejectedWhenAnyTouchedMonthIsFrozen() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.of(YearMonth.now().getYear(), 1, 5);
        freezeMonth(staffEmployeeId, YearMonth.from(start));
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(1), "إجازة",
                staffMembership);

        assertThatThrownBy(() -> leaveRequests.approve(clinicA, created.id(), managerMembership))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(historyOf(created.id())).extracting(LeaveRequest::status).containsExactly(LeaveStatus.pending);
    }

    @Test
    void approve_rejectedWhenRangeTouchesLaterFrozenMonth() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.of(YearMonth.now().getYear(), 1, 31);
        freezeMonth(staffEmployeeId, YearMonth.of(start.getYear(), 2));
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(1), "إجازة",
                staffMembership);

        assertThatThrownBy(() -> leaveRequests.approve(clinicA, created.id(), managerMembership))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(historyOf(created.id())).extracting(LeaveRequest::status).containsExactly(LeaveStatus.pending);
    }

    @Test
    void approve_allowedWhenFrozenMonthWasUnlocked() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.of(YearMonth.now().getYear(), 2, 5);
        freezeMonth(staffEmployeeId, YearMonth.from(start));
        unlockMonth(staffEmployeeId, YearMonth.from(start));
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(1), "إجازة",
                staffMembership);

        assertThat(leaveRequests.approve(clinicA, created.id(), managerMembership).status())
                .isEqualTo(LeaveStatus.approved);
    }

    @Test
    void approvedLeave_makesTheRangeNonWorkdayForThatEmployeeOnly() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = workingDay(LocalDate.now().plusDays(7));
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(1),
                "إجازة سنوية", staffMembership);
        leaveRequests.approve(clinicA, created.id(), managerMembership);

        assertThat(calendar.isWorkday(clinicA, start, staffEmployeeId)).isFalse();
        assertThat(calendar.isWorkday(clinicA, start, otherEmployeeId)).isTrue();
    }

    @Test
    void approvedLeave_reducesOnlyThatEmployeesWorkdayCount() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.of(2027, 1, 4);
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start.plusDays(1), start.plusDays(1),
                "موعد طبي", staffMembership);
        leaveRequests.approve(clinicA, created.id(), managerMembership);

        assertThat(calendar.workdaysBetween(clinicA, start, start.plusDays(2), staffEmployeeId)).isEqualTo(2);
        assertThat(calendar.workdaysBetween(clinicA, start, start.plusDays(2), otherEmployeeId)).isEqualTo(3);
    }

    @Test
    void rejectedLeave_leavesTheWorkdayAlone() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = workingDay(LocalDate.now().plusDays(7));
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start, "إجازة", staffMembership);
        leaveRequests.reject(clinicA, created.id(), "لا", managerMembership);

        assertThat(calendar.isWorkday(clinicA, start, staffEmployeeId)).isTrue();
    }

    @Test
    void cancel_removesPendingRequestAndFreesTheRange() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start.plusDays(2), "إجازة",
                staffMembership);

        leaveRequests.cancel(clinicA, created.id(), staffEmployeeId, staffMembership);

        assertThat(leaveRequests.listPendingForApprover(clinicA, managerMembership, "manager")).isEmpty();
        assertThat(leaveRequests
                .submit(clinicA, staffEmployeeId, start, start.plusDays(2), "إجازة ثانية", staffMembership).status())
                .isEqualTo(LeaveStatus.pending);
        assertThat(activityLogService.forDay(clinicA, LocalDate.now(), "leave.cancel"))
                .extracting(ActivityLogService.Entry::action).contains("leave.cancel");
    }

    @Test
    void cancel_rejectedForSomeoneElsesRequest() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start, "إجازة", staffMembership);

        assertThatThrownBy(() -> leaveRequests.cancel(clinicA, created.id(), staffEmployeeId, otherMembership))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(leaveRequests.listPendingForApprover(clinicA, managerMembership, "manager")).hasSize(1);
    }

    @Test
    void approverQueue_hidesOwnRequestAndManagerRequestsFromManager() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        leaveRequests.submit(clinicA, staffEmployeeId, start, start, "موظف", staffMembership);
        UUID secondManager = insertEmployeeMembership("مدير آخر", "manager");
        UUID secondManagerEmployeeId = employeeIdOf(secondManager);
        leaveRequests.submit(clinicA, secondManagerEmployeeId, start, start, "مدير", secondManager);

        List<LeaveRequest> managerQueue = leaveRequests.listPendingForApprover(clinicA, managerMembership, "manager");
        List<LeaveRequest> ownerQueue = leaveRequests.listPendingForApprover(clinicA, ownerMembership, "owner");

        assertThat(managerQueue).extracting(LeaveRequest::employeeId).containsExactly(staffEmployeeId);
        assertThat(ownerQueue).extracting(LeaveRequest::employeeId)
                .containsExactlyInAnyOrder(staffEmployeeId, secondManagerEmployeeId);
    }

    @Test
    void managerRequestNotifiesOwnersButNotOtherManagers() throws Exception {
        TenantContext.set(clinicA);
        UUID secondManager = insertEmployeeMembership("مدير آخر", "manager");
        UUID secondManagerEmployeeId = employeeIdOf(secondManager);

        leaveRequests.submit(clinicA, secondManagerEmployeeId, LocalDate.now().plusDays(7),
                LocalDate.now().plusDays(7), "إجازة", secondManager);

        assertThat(kindsFor(ownerMembership)).contains(NotificationKind.LEAVE_REQUESTED);
        assertThat(kindsFor(managerMembership)).doesNotContain(NotificationKind.LEAVE_REQUESTED);
    }

    @Test
    void employeeHistory_isScopedToThatEmployee() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        leaveRequests.submit(clinicA, staffEmployeeId, start, start, "إجازة", staffMembership);
        leaveRequests.submit(clinicA, otherEmployeeId, start, start, "إجازة أخرى", otherMembership);

        assertThat(leaveRequests.listForEmployee(clinicA, staffEmployeeId)).hasSize(1);
    }

    @Test
    void otherClinic_cannotSeeOrDecideTheRequest() throws Exception {
        TenantContext.set(clinicA);
        LocalDate start = LocalDate.now().plusDays(7);
        LeaveRequest created = leaveRequests.submit(clinicA, staffEmployeeId, start, start, "إجازة", staffMembership);

        TenantContext.set(clinicB);
        assertThat(leaveRequests.listForEmployee(clinicB, staffEmployeeId)).isEmpty();
        assertThat(leaveRequests.listPendingForApprover(clinicB, ownerMembership, "owner")).isEmpty();
        assertThatThrownBy(() -> leaveRequests.approve(clinicB, created.id(), ownerMembership))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mayDecide_followsTheRoleHierarchy() throws Exception {
        assertThat(LeaveRequestService.mayDecide("owner", "assistant", false)).isTrue();
        assertThat(LeaveRequestService.mayDecide("manager", "assistant", false)).isTrue();
        assertThat(LeaveRequestService.mayDecide("manager", "manager", false)).isFalse();
        assertThat(LeaveRequestService.mayDecide("manager", "owner", false)).isFalse();
        assertThat(LeaveRequestService.mayDecide("assistant", "assistant", false)).isFalse();
        assertThat(LeaveRequestService.mayDecide("owner", "owner", true)).isFalse();
    }

    private List<LeaveRequest> historyOf(UUID leaveRequestId) throws Exception {
        return leaveRequests.listForEmployee(clinicA, employeeIdOfLeaveRequest(leaveRequestId));
    }

    private List<NotificationKind> kindsFor(UUID membershipId) {
        return notifications.recent(clinicA, membershipId, 50).stream()
                .map(NotificationService.Notification::kind)
                .toList();
    }

    private static LocalDate workingDay(LocalDate date) {
        return date.getDayOfWeek().getValue() >= 6 ? date.plusDays(2) : date;
    }

    private UUID insertEmployeeMembership(String name, String roleCode) throws Exception {
        try (Connection conn = superuser()) {
            UUID employeeId = TestFixtures.insertEmployee(conn, clinicA, name);
            return employeeMembership(conn, employeeId, roleCode);
        }
    }

    private void freezeMonth(UUID employeeId, YearMonth month) throws Exception {
        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .insertInto(EVALUATION_SNAPSHOT, EVALUATION_SNAPSHOT.CLINIC_ID, EVALUATION_SNAPSHOT.EMPLOYEE_ID,
                            EVALUATION_SNAPSHOT.PERIOD_MONTH, EVALUATION_SNAPSHOT.FINAL_SCORE,
                            EVALUATION_SNAPSHOT.INCENTIVE_AMOUNT)
                    .values(clinicA, employeeId, month.atDay(1), BigDecimal.TEN, BigDecimal.ZERO)
                    .execute();
        }
    }

    private void unlockMonth(UUID employeeId, YearMonth month) throws Exception {
        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .update(EVALUATION_SNAPSHOT)
                    .set(EVALUATION_SNAPSHOT.UNLOCKED_AT, java.time.OffsetDateTime.now())
                    .where(EVALUATION_SNAPSHOT.EMPLOYEE_ID.eq(employeeId))
                    .and(EVALUATION_SNAPSHOT.PERIOD_MONTH.eq(month.atDay(1)))
                    .execute();
        }
    }

    private void linkEmployeeToMembership(UUID membershipId, UUID employeeId) throws Exception {
        try (Connection conn = superuser()) {
            DSL.using(conn, SQLDialect.POSTGRES)
                    .update(MEMBERSHIP)
                    .set(MEMBERSHIP.EMPLOYEE_ID, employeeId)
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .execute();
        }
    }

    private UUID employeeIdOf(UUID membershipId) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .select(MEMBERSHIP.EMPLOYEE_ID)
                    .from(MEMBERSHIP)
                    .where(MEMBERSHIP.ID.eq(membershipId))
                    .fetchOne(MEMBERSHIP.EMPLOYEE_ID);
        }
    }

    private UUID employeeIdOfLeaveRequest(UUID leaveRequestId) throws Exception {
        try (Connection conn = superuser()) {
            return DSL.using(conn, SQLDialect.POSTGRES)
                    .select(com.clinicos.shared.jooq.tables.LeaveRequest.LEAVE_REQUEST.EMPLOYEE_ID)
                    .from(com.clinicos.shared.jooq.tables.LeaveRequest.LEAVE_REQUEST)
                    .where(com.clinicos.shared.jooq.tables.LeaveRequest.LEAVE_REQUEST.ID.eq(leaveRequestId))
                    .fetchOne(com.clinicos.shared.jooq.tables.LeaveRequest.LEAVE_REQUEST.EMPLOYEE_ID);
        }
    }

    private static Connection superuser() throws Exception {
        return POSTGRES.createConnection("");
    }
}
