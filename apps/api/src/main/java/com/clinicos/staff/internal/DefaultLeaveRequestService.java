package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.Employee.EMPLOYEE;
import static com.clinicos.shared.jooq.tables.EvaluationSnapshot.EVALUATION_SNAPSHOT;
import static com.clinicos.shared.jooq.tables.LeaveRequest.LEAVE_REQUEST;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;

import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.jooq.enums.LeaveRequestStatus;
import com.clinicos.shared.jooq.enums.MembershipStatus;
import com.clinicos.shared.jooq.tables.records.LeaveRequestRecord;
import com.clinicos.staff.api.LeaveRequestService;
import com.clinicos.staff.api.LeaveRequestService.LeaveRequest;

@Service
public class DefaultLeaveRequestService implements LeaveRequestService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final NotificationService notificationService;
    private final ActivityLogService activityLogService;

    public DefaultLeaveRequestService(DSLContext dsl, TransactionTemplate transactionTemplate,
            NotificationService notificationService, ActivityLogService activityLogService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.notificationService = notificationService;
        this.activityLogService = activityLogService;
    }

    @Override
    public LeaveRequest submit(UUID clinicId, UUID employeeId, LocalDate start, LocalDate end, String reason,
            UUID actorMembershipId) {
        if (employeeId == null) {
            throw new IllegalArgumentException("الموظف مطلوب");
        }
        if (start == null || end == null) {
            throw new IllegalArgumentException("تواريخ الإجازة مطلوبة");
        }
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("تاريخ النهاية يجب أن يكون بعد تاريخ البداية أو مساوياً له");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("سبب الإجازة مطلوب");
        }
        return doSubmit(clinicId, employeeId, start, end, reason, actorMembershipId);
    }

    private LeaveRequest doSubmit(UUID clinicId, UUID employeeId, LocalDate start, LocalDate end, String reason,
            UUID actorMembershipId) {
        return transactionTemplate.execute(status -> {
            if (!dsl.fetchExists(EMPLOYEE, EMPLOYEE.ID.eq(employeeId)
                    .and(EMPLOYEE.CLINIC_ID.eq(clinicId))
                    .and(EMPLOYEE.ARCHIVED_AT.isNull()))) {
                throw new IllegalArgumentException("الموظف غير موجود");
            }
            UUID requesterMembership = membershipOfEmployee(clinicId, employeeId);
            if (requesterMembership == null || !requesterMembership.equals(actorMembershipId)) {
                throw new IllegalArgumentException("لا يمكن تقديم طلب إجازة نيابة عن موظف آخر");
            }
            String requesterRole = roleOfMembership(clinicId, actorMembershipId);
            if ("owner".equals(requesterRole)
                    && !hasOtherActiveOwnerTx(clinicId, actorMembershipId)) {
                throw new IllegalArgumentException("المالك الوحيد يسجل إجازته من تقويم العمل");
            }
            rejectIfOverlaps(clinicId, employeeId, start, end);
            LeaveRequestRecord inserted;
            try {
                inserted = dsl.insertInto(LEAVE_REQUEST,
                        LEAVE_REQUEST.CLINIC_ID,
                        LEAVE_REQUEST.EMPLOYEE_ID,
                        LEAVE_REQUEST.START_DATE,
                        LEAVE_REQUEST.END_DATE,
                        LEAVE_REQUEST.REASON)
                        .values(clinicId, employeeId, start, end, reason.strip())
                        .returning(LEAVE_REQUEST.fields())
                        .fetchOne();
            } catch (DataIntegrityViolationException violation) {
                throw overlapViolation(violation);
            }
            Set<String> approverRoles = "owner".equals(requesterRole) || "manager".equals(requesterRole)
                    ? Set.of("owner")
                    : Set.of("owner", "manager");
            notificationService.notifyRoles(clinicId, actorMembershipId, approverRoles, NotificationKind.LEAVE_REQUESTED,
                    Map.of("employee", employeeNameOf(clinicId, employeeId), "range", range(inserted)));
            activityLogService.log(clinicId, actorMembershipId, "leave.submit", "leave_request", inserted.getId(),
                    Map.of("start", start.toString(), "end", end.toString()));
            return toLeaveRequest(inserted);
        });
    }

    @Override
    public LeaveRequest approve(UUID clinicId, UUID leaveRequestId, UUID actorMembershipId) {
        return decide(clinicId, leaveRequestId, LeaveRequestStatus.approved, null, actorMembershipId);
    }

    @Override
    public LeaveRequest reject(UUID clinicId, UUID leaveRequestId, String note, UUID actorMembershipId) {
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("سبب الرفض مطلوب");
        }
        return decide(clinicId, leaveRequestId, LeaveRequestStatus.rejected, note.strip(), actorMembershipId);
    }

    private LeaveRequest decide(UUID clinicId, UUID leaveRequestId, LeaveRequestStatus target, String note,
            UUID actorMembershipId) {
        return transactionTemplate.execute(status -> {
            LeaveRequestRecord current = pendingRow(clinicId, leaveRequestId);
            UUID employeeId = current.getEmployeeId();
            boolean self = actorMembershipId.equals(membershipOfEmployee(clinicId, employeeId));
            String actorRole = roleOfMembership(clinicId, actorMembershipId);
            String requesterRole = roleOfEmployee(clinicId, employeeId);
            if (!LeaveRequestService.mayDecide(actorRole, requesterRole, self)) {
                throw new IllegalArgumentException("لا تملك صلاحية اتخاذ القرار على هذا الطلب");
            }
            if (target == LeaveRequestStatus.approved && isClosedMonth(clinicId, employeeId, current)) {
                throw new IllegalArgumentException("لا يمكن اعتماد إجازة في شهر مغلق لأن التقييم قد تم تثبيته");
            }
            int updated = dsl.update(LEAVE_REQUEST)
                    .set(LEAVE_REQUEST.STATUS, target)
                    .set(LEAVE_REQUEST.DECIDED_BY, actorMembershipId)
                    .set(LEAVE_REQUEST.DECIDED_AT, OffsetDateTime.now())
                    .set(LEAVE_REQUEST.DECISION_NOTE, note)
                    .where(LEAVE_REQUEST.CLINIC_ID.eq(clinicId))
                    .and(LEAVE_REQUEST.ID.eq(leaveRequestId))
                    .and(LEAVE_REQUEST.STATUS.eq(LeaveRequestStatus.pending))
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("الطلب ليس بانتظار القرار");
            }
            LeaveRequestRecord decided = dsl.selectFrom(LEAVE_REQUEST)
                    .where(LEAVE_REQUEST.CLINIC_ID.eq(clinicId))
                    .and(LEAVE_REQUEST.ID.eq(leaveRequestId))
                    .fetchOne();
            Map<String, String> payload = note == null
                    ? Map.of("employee", employeeNameOf(clinicId, employeeId), "range", range(current))
                    : Map.of("employee", employeeNameOf(clinicId, employeeId), "range", range(current),
                            "reason", note);
            notificationService.notifyEmployee(clinicId, actorMembershipId, employeeId,
                    target == LeaveRequestStatus.approved
                            ? NotificationKind.LEAVE_APPROVED
                            : NotificationKind.LEAVE_REJECTED,
                    payload);
            activityLogService.logRequired(clinicId, actorMembershipId,
                    target == LeaveRequestStatus.approved ? "leave.approve" : "leave.reject", "leave_request",
                    leaveRequestId, note == null ? Map.of("range", range(current))
                            : Map.of("range", range(current), "note", note));
            return toLeaveRequest(decided);
        });
    }

    @Override
    public void cancel(UUID clinicId, UUID leaveRequestId, UUID employeeId, UUID actorMembershipId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (!actorMembershipId.equals(membershipOfEmployee(clinicId, employeeId))) {
                throw new IllegalArgumentException("لا يمكن إلغاء طلب إجازة لا يخصك");
            }
            int removed = dsl.update(LEAVE_REQUEST)
                    .set(LEAVE_REQUEST.STATUS, LeaveRequestStatus.rejected)
                    .set(LEAVE_REQUEST.DECIDED_BY, actorMembershipId)
                    .set(LEAVE_REQUEST.DECIDED_AT, OffsetDateTime.now())
                    .set(LEAVE_REQUEST.DECISION_NOTE, "تم الإلغاء بواسطة صاحب الطلب")
                    .where(LEAVE_REQUEST.CLINIC_ID.eq(clinicId))
                    .and(LEAVE_REQUEST.ID.eq(leaveRequestId))
                    .and(LEAVE_REQUEST.EMPLOYEE_ID.eq(employeeId))
                    .and(LEAVE_REQUEST.STATUS.eq(LeaveRequestStatus.pending))
                    .execute();
            if (removed == 0) {
                throw new IllegalArgumentException("لا يوجد طلب إجازة بانتظار الإلغاء");
            }
            activityLogService.log(clinicId, actorMembershipId, "leave.cancel", "leave_request", leaveRequestId,
                    null);
        });
    }

    @Override
    public List<LeaveRequest> listPendingForApprover(UUID clinicId, UUID actorMembershipId) {
        return transactionTemplate.execute(status -> {
            String actorRoleCode = roleOfMembership(clinicId, actorMembershipId);
            if (!"owner".equals(actorRoleCode) && !"manager".equals(actorRoleCode)) {
                return List.of();
            }
            Condition requesterVisible = "owner".equals(actorRoleCode)
                    ? DSL.noCondition()
                    : roleOfEmployeeExists(clinicId, "owner").not()
                            .and(roleOfEmployeeExists(clinicId, "manager").not());
            return dsl.select(LEAVE_REQUEST.fields())
                    .select(EMPLOYEE.NAME)
                    .from(LEAVE_REQUEST)
                    .join(EMPLOYEE).on(EMPLOYEE.ID.eq(LEAVE_REQUEST.EMPLOYEE_ID))
                    .where(LEAVE_REQUEST.CLINIC_ID.eq(clinicId))
                    .and(LEAVE_REQUEST.STATUS.eq(LeaveRequestStatus.pending))
                    .and(requesterVisible)
                    .and(LEAVE_REQUEST.EMPLOYEE_ID.notIn(
                            dsl.select(MEMBERSHIP.EMPLOYEE_ID)
                                    .from(MEMBERSHIP)
                                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                                    .and(MEMBERSHIP.ID.eq(actorMembershipId))
                                    .and(MEMBERSHIP.EMPLOYEE_ID.isNotNull())))
                    .orderBy(LEAVE_REQUEST.CREATED_AT.asc())
                    .fetch(this::toLeaveRequestJoined);
        });
    }

    @Override
    public List<LeaveRequest> listForEmployee(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> dsl.select(LEAVE_REQUEST.fields())
                .select(EMPLOYEE.NAME)
                .from(LEAVE_REQUEST)
                .join(EMPLOYEE).on(EMPLOYEE.ID.eq(LEAVE_REQUEST.EMPLOYEE_ID))
                .where(LEAVE_REQUEST.CLINIC_ID.eq(clinicId))
                .and(LEAVE_REQUEST.EMPLOYEE_ID.eq(employeeId))
                .orderBy(LEAVE_REQUEST.CREATED_AT.desc())
                .fetch(this::toLeaveRequestJoined));
    }

    @Override
    public boolean hasOtherActiveOwner(UUID clinicId, UUID actorMembershipId) {
        return transactionTemplate.execute(status -> hasOtherActiveOwnerTx(clinicId, actorMembershipId));
    }

    private boolean hasOtherActiveOwnerTx(UUID clinicId, UUID actorMembershipId) {
        return dsl.fetchExists(dsl.selectOne()
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.ID.ne(actorMembershipId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .and(ROLE.CODE.eq("owner")));
    }

    private void rejectIfOverlaps(UUID clinicId, UUID employeeId, LocalDate start, LocalDate end) {
        if (dsl.fetchExists(LEAVE_REQUEST, LEAVE_REQUEST.CLINIC_ID.eq(clinicId)
                .and(LEAVE_REQUEST.EMPLOYEE_ID.eq(employeeId))
                .and(LEAVE_REQUEST.STATUS.in(LeaveRequestStatus.pending, LeaveRequestStatus.approved))
                .and(LEAVE_REQUEST.START_DATE.lessOrEqual(end))
                .and(LEAVE_REQUEST.END_DATE.greaterOrEqual(start)))) {
            throw new IllegalArgumentException("توجد إجازة مسجلة بنفس الفترة");
        }
    }

    static IllegalArgumentException overlapViolation(DataIntegrityViolationException violation) {
        for (Throwable cause = violation; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23P01".equals(sql.getSQLState())) {
                return new IllegalArgumentException("توجد إجازة مسجلة بنفس الفترة", violation);
            }
        }
        throw violation;
    }

    private LeaveRequestRecord pendingRow(UUID clinicId, UUID leaveRequestId) {
        LeaveRequestRecord row = dsl.selectFrom(LEAVE_REQUEST)
                .where(LEAVE_REQUEST.CLINIC_ID.eq(clinicId))
                .and(LEAVE_REQUEST.ID.eq(leaveRequestId))
                .fetchOne();
        if (row == null) {
            throw new IllegalArgumentException("لا يوجد طلب إجازة بهذا المعرف");
        }
        if (row.getStatus() != LeaveRequestStatus.pending) {
            throw new IllegalArgumentException("الطلب ليس بانتظار القرار");
        }
        return row;
    }

    private boolean isClosedMonth(UUID clinicId, UUID employeeId, LeaveRequestRecord request) {
        return dsl.fetchExists(EVALUATION_SNAPSHOT, EVALUATION_SNAPSHOT.CLINIC_ID.eq(clinicId)
                .and(EVALUATION_SNAPSHOT.EMPLOYEE_ID.eq(employeeId))
                .and(EVALUATION_SNAPSHOT.PERIOD_MONTH
                        .between(YearMonth.from(request.getStartDate()).atDay(1),
                                YearMonth.from(request.getEndDate()).atEndOfMonth()))
                .and(EVALUATION_SNAPSHOT.UNLOCKED_AT.isNull()));
    }

    private Condition roleOfEmployeeExists(UUID clinicId, String roleCode) {
        return DSL.exists(dsl.selectOne()
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.EMPLOYEE_ID.eq(LEAVE_REQUEST.EMPLOYEE_ID))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .and(ROLE.CODE.eq(roleCode)));
    }

    private UUID membershipOfEmployee(UUID clinicId, UUID employeeId) {
        return dsl.select(MEMBERSHIP.ID)
                .from(MEMBERSHIP)
                .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .fetchOne(MEMBERSHIP.ID);
    }

    private String roleOfMembership(UUID clinicId, UUID membershipId) {
        return dsl.select(ROLE.CODE)
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.ID.eq(membershipId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .fetchOne(ROLE.CODE);
    }

    private String roleOfEmployee(UUID clinicId, UUID employeeId) {
        return dsl.select(ROLE.CODE)
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                .and(MEMBERSHIP.STATUS.eq(MembershipStatus.active))
                .fetchOne(ROLE.CODE);
    }

    private String employeeNameOf(UUID clinicId, UUID employeeId) {
        return dsl.select(EMPLOYEE.NAME)
                .from(EMPLOYEE)
                .where(EMPLOYEE.CLINIC_ID.eq(clinicId))
                .and(EMPLOYEE.ID.eq(employeeId))
                .fetchOne(EMPLOYEE.NAME);
    }

    private static String range(LeaveRequestRecord row) {
        return row.getStartDate().equals(row.getEndDate())
                ? row.getStartDate().toString()
                : row.getStartDate() + " - " + row.getEndDate();
    }

    private LeaveRequest toLeaveRequest(LeaveRequestRecord r) {
        return toLeaveRequest(r, null);
    }

    private LeaveRequest toLeaveRequestJoined(Record record) {
        LeaveRequestRecord r = record.into(LEAVE_REQUEST);
        return toLeaveRequest(r, record.getValue(EMPLOYEE.NAME));
    }

    private LeaveRequest toLeaveRequest(LeaveRequestRecord r, String employeeName) {
        return new LeaveRequest(r.getId(), r.getEmployeeId(), employeeName, r.getStartDate(), r.getEndDate(),
                r.getReason(), LeaveRequestService.LeaveStatus.of(r.getStatus().getLiteral()), r.getDecidedBy(),
                r.getDecisionNote(), r.getCreatedAt());
    }
}
