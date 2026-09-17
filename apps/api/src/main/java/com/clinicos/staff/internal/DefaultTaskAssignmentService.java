package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.TaskAssignment.TASK_ASSIGNMENT;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.jooq.enums.AssignmentProposer;
import com.clinicos.shared.jooq.enums.AssignmentStatus;
import com.clinicos.shared.jooq.tables.records.TaskAssignmentRecord;
import com.clinicos.staff.api.TaskAssignmentService;
import com.clinicos.staff.api.TaskAssignmentService.Assignment;
import com.clinicos.staff.api.TaskAssignmentService.AssignmentForm;
import com.clinicos.staff.api.TaskAssignmentService.Proposer;

@Service
public class DefaultTaskAssignmentService implements TaskAssignmentService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultTaskAssignmentService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<Assignment> today(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> dsl.selectFrom(TASK_ASSIGNMENT)
                .where(TASK_ASSIGNMENT.CLINIC_ID.eq(clinicId))
                .and(TASK_ASSIGNMENT.EMPLOYEE_ID.eq(employeeId))
                .and(TASK_ASSIGNMENT.DUE_DATE.isNull()
                        .or(TASK_ASSIGNMENT.DUE_DATE.greaterOrEqual(LocalDate.now())))
                .orderBy(TASK_ASSIGNMENT.ASSIGNED_AT.desc())
                .fetch(this::toAssignment));
    }

    @Override
    public List<Assignment> listForMonth(UUID clinicId, UUID employeeId, YearMonth month) {
        OffsetDateTime from = month.atDay(1).atStartOfDay()
                .atOffset(OffsetDateTime.now().getOffset());
        OffsetDateTime to = month.plusMonths(1).atDay(1).atStartOfDay()
                .atOffset(OffsetDateTime.now().getOffset());
        return transactionTemplate.execute(status ->
                dsl.selectFrom(TASK_ASSIGNMENT)
                        .where(TASK_ASSIGNMENT.CLINIC_ID.eq(clinicId))
                        .and(TASK_ASSIGNMENT.EMPLOYEE_ID.eq(employeeId))
                        .and(TASK_ASSIGNMENT.ASSIGNED_AT.greaterOrEqual(from))
                        .and(TASK_ASSIGNMENT.ASSIGNED_AT.lessThan(to))
                        .orderBy(TASK_ASSIGNMENT.ASSIGNED_AT.asc())
                        .fetch(this::toAssignment));
    }

    @Override
    public Assignment approve(UUID clinicId, UUID assignmentId, UUID approvedByMembershipId) {
        return review(clinicId, assignmentId, AssignmentStatus.approved, approvedByMembershipId, null);
    }

    @Override
    public Assignment reject(UUID clinicId, UUID assignmentId, UUID approvedByMembershipId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("سبب الرفض مطلوب");
        }
        return review(clinicId, assignmentId, AssignmentStatus.rejected, approvedByMembershipId, reason.strip());
    }

    private Assignment review(UUID clinicId, UUID assignmentId, AssignmentStatus target,
            UUID approvedByMembershipId, String reason) {
        return transactionTemplate.execute(status -> {
            int updated = dsl.update(TASK_ASSIGNMENT)
                    .set(TASK_ASSIGNMENT.STATUS, target)
                    .set(TASK_ASSIGNMENT.APPROVED_BY, approvedByMembershipId)
                    .set(TASK_ASSIGNMENT.APPROVED_AT, OffsetDateTime.now())
                    .where(TASK_ASSIGNMENT.CLINIC_ID.eq(clinicId))
                    .and(TASK_ASSIGNMENT.ID.eq(assignmentId))
                    .and(TASK_ASSIGNMENT.STATUS.eq(AssignmentStatus.pending))
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("المهمة ليست بانتظار الاعتماد");
            }
            return dsl.selectFrom(TASK_ASSIGNMENT)
                    .where(TASK_ASSIGNMENT.CLINIC_ID.eq(clinicId))
                    .and(TASK_ASSIGNMENT.ID.eq(assignmentId))
                    .fetchOne(this::toAssignment);
        });
    }

    @Override
    public Assignment markDone(UUID clinicId, UUID employeeId, UUID assignmentId, UUID proofPhotoId) {
        return transactionTemplate.execute(status -> {
            int updated = dsl.update(TASK_ASSIGNMENT)
                    .set(TASK_ASSIGNMENT.DONE_AT, OffsetDateTime.now())
                    .set(TASK_ASSIGNMENT.PROOF_PHOTO_ID, proofPhotoId)
                    .where(TASK_ASSIGNMENT.ID.eq(assignmentId))
                    .and(TASK_ASSIGNMENT.CLINIC_ID.eq(clinicId))
                    .and(TASK_ASSIGNMENT.EMPLOYEE_ID.eq(employeeId))
                    .and(TASK_ASSIGNMENT.STATUS.eq(AssignmentStatus.approved))
                    .and(TASK_ASSIGNMENT.DONE_AT.isNull())
                    .execute();
            if (updated > 0) {
                return dsl.selectFrom(TASK_ASSIGNMENT)
                        .where(TASK_ASSIGNMENT.ID.eq(assignmentId))
                        .fetchOne(this::toAssignment);
            }
            TaskAssignmentRecord existing = dsl.selectFrom(TASK_ASSIGNMENT)
                    .where(TASK_ASSIGNMENT.ID.eq(assignmentId))
                    .and(TASK_ASSIGNMENT.CLINIC_ID.eq(clinicId))
                    .and(TASK_ASSIGNMENT.EMPLOYEE_ID.eq(employeeId))
                    .fetchOne();
            if (existing != null && existing.getDoneAt() != null) {
                return toAssignment(existing);
            }
            throw new IllegalArgumentException("المهمة غير معتمدة أو غير موجودة");
        });
    }

    @Override
    public Assignment propose(UUID clinicId, UUID proposerEmployeeId, AssignmentForm form,
            Proposer proposer) {
        return transactionTemplate.execute(status -> {
            String name = form.name();
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("اسم المهمة مطلوب");
            }
            if (form.employeeId() == null) {
                throw new IllegalArgumentException("الموظف مطلوب");
            }
            LocalDate dueDate = form.dueDate() != null ? form.dueDate() : LocalDate.now();
            return dsl.insertInto(TASK_ASSIGNMENT,
                    TASK_ASSIGNMENT.CLINIC_ID,
                    TASK_ASSIGNMENT.EMPLOYEE_ID,
                    TASK_ASSIGNMENT.NAME,
                    TASK_ASSIGNMENT.PROPOSED_BY,
                    TASK_ASSIGNMENT.DUE_DATE)
                    .values(clinicId, form.employeeId(), name.strip(),
                            toDbProposer(proposer), dueDate)
                    .returning(TASK_ASSIGNMENT.fields())
                    .fetchOne(this::toAssignment);
        });
    }

    private Assignment toAssignment(TaskAssignmentRecord r) {
        return new Assignment(
                r.getId(),
                r.getName(),
                r.getDueDate(),
                r.getStatus().getLiteral(),
                r.getDoneAt(),
                r.getProofPhotoId(),
                r.getProposedBy().getLiteral(),
                r.getApprovedAt());
    }

    private static AssignmentProposer toDbProposer(Proposer proposer) {
        return switch (proposer) {
            case MANAGER -> AssignmentProposer.manager;
            case SELF -> AssignmentProposer.employee;
        };
    }
}