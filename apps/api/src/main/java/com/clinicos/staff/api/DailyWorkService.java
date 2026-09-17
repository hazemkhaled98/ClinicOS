package com.clinicos.staff.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

public interface DailyWorkService {

    List<DailyTask> today(UUID clinicId, UUID employeeId);

    /**
     * Every done completion in the month with its manager review state
     * (UC-004 step 4 review grid). Approving/rejecting uses
     * {@link #approveReview} / {@link #rejectReview}.
     */
    List<CompletionRow> listForMonth(UUID clinicId, UUID employeeId, YearMonth month);

    /**
     * Same shape as {@link #today} for a specific date, plus the manager's
     * review state on each completion (UC-004). Used by the evaluation
     * review screen.
     */
    List<DailyTask> forDate(UUID clinicId, UUID employeeId, LocalDate date);

    /** Marks one completion approved (UC-004 step 4; only done completions). */
    void approveReview(UUID clinicId, UUID dailyRecordId, UUID taskDefinitionId, UUID reviewedByMembershipId);

    /** Marks one completion rejected with a mandatory reason (UC-004 A1). */
    void rejectReview(UUID clinicId, UUID dailyRecordId, UUID taskDefinitionId, UUID reviewedByMembershipId, String reason);

    void complete(UUID clinicId, UUID employeeId, UUID taskDefinitionId, UUID photoId);

    void uncomplete(UUID clinicId, UUID employeeId, UUID taskDefinitionId);

    record DailyTask(
            UUID taskDefinitionId,
            String name,
            String dimension,
            String frequency,
            boolean requiresPhoto,
            boolean done,
            OffsetDateTime completedAt,
            UUID photoId,
            LocalDate lastCompletedDate,
            Integer everyN,
            String intervalUnit,
            String reviewStatus,
            String reviewReason,
            OffsetDateTime reviewedAt) {
    }

    record CompletionRow(
            UUID dailyRecordId,
            UUID taskDefinitionId,
            String taskName,
            LocalDate workDate,
            String reviewStatus,
            String reviewReason,
            UUID photoId,
            boolean requiresPhoto) {
    }
}