package com.clinicos.staff.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface TaskAssignmentService {

    List<Assignment> today(UUID clinicId, UUID employeeId);

    /** All assignments assigned in the given month (UC-004 review + scoring). */
    List<Assignment> listForMonth(UUID clinicId, UUID employeeId, YearMonth month);

    /** Approves a pending assignment (BR-G12; sets approved_by / approved_at). */
    Assignment approve(UUID clinicId, UUID assignmentId, UUID approvedByMembershipId);

    /** Rejects a pending assignment with a mandatory reason (UC-004 A1). */
    Assignment reject(UUID clinicId, UUID assignmentId, UUID approvedByMembershipId, String reason);

    Assignment markDone(UUID clinicId, UUID employeeId, UUID assignmentId, UUID proofPhotoId);

    Assignment propose(UUID clinicId, UUID proposerEmployeeId, AssignmentForm form,
            Proposer proposer);

    enum Proposer {
        MANAGER,
        SELF
    }

    record Assignment(
            UUID id,
            String name,
            LocalDate dueDate,
            String status,
            OffsetDateTime doneAt,
            UUID proofPhotoId,
            String proposedBy,
            OffsetDateTime approvedAt) {
    }

    record AssignmentForm(
            UUID employeeId,
            String name,
            LocalDate dueDate) {
    }
}