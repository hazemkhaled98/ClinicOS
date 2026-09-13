package com.clinicos.staff.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface TaskAssignmentService {

    List<Assignment> today(UUID clinicId, UUID employeeId);

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
            UUID proofPhotoId) {
    }

    record AssignmentForm(
            UUID employeeId,
            String name,
            LocalDate dueDate) {
    }
}