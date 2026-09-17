package com.clinicos.staff.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only bulk data provider for the monthly evaluation engine.
 * One call per (employee, month) assembles every input the scoring
 * algorithm needs from the staff tables — task definitions, approved
 * completions, logged dates, attendance, and assignments.
 *
 * <p>Only door the evaluation module uses for staff data. Querying
 * staff tables directly from evaluation code is prohibited.
 */
public interface EvaluationInputService {

    MonthData forMonth(UUID clinicId, UUID employeeId, YearMonth month);

    record MonthData(
            List<TaskDef> tasks,
            List<Completion> completions,
            List<LocalDate> loggedDates,
            List<AttendanceDay> attendance,
            List<AssignmentRecord> assignments,
            java.util.Map<UUID, LocalDate> lastRunByTask,
            BigDecimal volumeActual) {
    }

    /** In-scope task definition: archived=null, created_at ≤ month-end, employee-scoped. */
    record TaskDef(
            UUID id,
            String name,
            String dimension,
            String frequency,
            Integer everyN,
            String intervalUnit,
            LocalDateTime createdAt) {
    }

    /** Approved, done completion of one task on one day. */
    record Completion(
            UUID taskDefinitionId,
            LocalDate workDate) {
    }

    /** Self-check row for one working day (times local, nulls = absent). */
    record AttendanceDay(
            LocalDate workDate,
            LocalTime checkInTime,
            LocalTime checkOutTime) {
    }

    /** All assignments in the month for the employee. */
    record AssignmentRecord(
            UUID id,
            LocalDate dueDate,
            String status,
            LocalDateTime assignedAt,
            String proposedBy,
            OffsetDateTime doneAt) {
    }
}
