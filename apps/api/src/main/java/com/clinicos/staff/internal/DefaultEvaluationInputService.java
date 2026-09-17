package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.DailyRecord.DAILY_RECORD;
import static com.clinicos.shared.jooq.tables.DailyTaskCompletion.DAILY_TASK_COMPLETION;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.OperationsVolume.OPERATIONS_VOLUME;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.SelfCheck.SELF_CHECK;
import static com.clinicos.shared.jooq.tables.TaskAssignment.TASK_ASSIGNMENT;
import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.jooq.enums.TaskReviewStatus;
import com.clinicos.staff.api.EvaluationInputService;

@Service
public class DefaultEvaluationInputService implements EvaluationInputService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultEvaluationInputService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public MonthData forMonth(UUID clinicId, UUID employeeId, YearMonth month) {
        return transactionTemplate.execute(status -> {
            String roleCode = dsl.select(ROLE.CODE)
                    .from(MEMBERSHIP)
                    .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                    .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                    .and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                    .fetchOne(ROLE.CODE);
            if (roleCode == null) {
                return new MonthData(List.of(), List.of(), List.of(), List.of(), List.of(), Map.of(), null);
            }

            OffsetDateTime nextMonthStart = month.plusMonths(1).atDay(1).atStartOfDay()
                    .atOffset(OffsetDateTime.now().getOffset());
            List<TaskDef> tasks = dsl.selectFrom(TASK_DEFINITION)
                    .where(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                    .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                    .and(TASK_DEFINITION.CREATED_AT.lessThan(nextMonthStart))
                    .and(TASK_DEFINITION.ROLE_CODE.eq(roleCode)
                            .or(TASK_DEFINITION.ROLE_CODE.isNull().and(TASK_DEFINITION.EMPLOYEE_ID.isNull()))
                            .or(TASK_DEFINITION.EMPLOYEE_ID.eq(employeeId)))
                    .orderBy(TASK_DEFINITION.DISPLAY_ORDER.asc(), TASK_DEFINITION.NAME.asc())
                    .fetch(r -> new TaskDef(
                            r.getId(),
                            r.getName(),
                            r.getDimension().getLiteral(),
                            r.getFrequency().getLiteral(),
                            r.getEveryN(),
                            r.getIntervalUnit() == null ? null : r.getIntervalUnit().getLiteral(),
                            r.getCreatedAt().toLocalDateTime()));

            List<Completion> completions = dsl.select(
                            DAILY_TASK_COMPLETION.TASK_DEFINITION_ID,
                            DAILY_RECORD.WORK_DATE)
                    .from(DAILY_TASK_COMPLETION)
                    .join(DAILY_RECORD).on(DAILY_RECORD.ID.eq(DAILY_TASK_COMPLETION.DAILY_RECORD_ID))
                    .where(DAILY_RECORD.CLINIC_ID.eq(clinicId))
                    .and(DAILY_RECORD.EMPLOYEE_ID.eq(employeeId))
                    .and(DAILY_RECORD.WORK_DATE.greaterOrEqual(month.atDay(1)))
                    .and(DAILY_RECORD.WORK_DATE.lessThan(month.atEndOfMonth().plusDays(1)))
                    .and(DAILY_TASK_COMPLETION.DONE.isTrue())
                    .and(DAILY_TASK_COMPLETION.REVIEW_STATUS.eq(TaskReviewStatus.approved))
                    .fetch(r -> new Completion(
                            r.getValue(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID),
                            r.getValue(DAILY_RECORD.WORK_DATE)));

            List<LocalDate> loggedDates = dsl.select(DAILY_RECORD.WORK_DATE)
                    .from(DAILY_RECORD)
                    .where(DAILY_RECORD.CLINIC_ID.eq(clinicId))
                    .and(DAILY_RECORD.EMPLOYEE_ID.eq(employeeId))
                    .and(DAILY_RECORD.WORK_DATE.greaterOrEqual(month.atDay(1)))
                    .and(DAILY_RECORD.WORK_DATE.lessThan(month.atEndOfMonth().plusDays(1)))
                    .fetch(DAILY_RECORD.WORK_DATE);

            List<AttendanceDay> attendance = dsl.selectFrom(SELF_CHECK)
                    .where(SELF_CHECK.CLINIC_ID.eq(clinicId))
                    .and(SELF_CHECK.EMPLOYEE_ID.eq(employeeId))
                    .and(SELF_CHECK.WORK_DATE.greaterOrEqual(month.atDay(1)))
                    .and(SELF_CHECK.WORK_DATE.lessThan(month.atEndOfMonth().plusDays(1)))
                    .orderBy(SELF_CHECK.WORK_DATE.asc())
                    .fetch(r -> new AttendanceDay(
                            r.getWorkDate(),
                            r.getCheckedInAt().toLocalTime(),
                            r.getCheckedOutAt() == null ? null : r.getCheckedOutAt().toLocalTime()));

            List<AssignmentRecord> assignments = dsl.selectFrom(TASK_ASSIGNMENT)
                    .where(TASK_ASSIGNMENT.CLINIC_ID.eq(clinicId))
                    .and(TASK_ASSIGNMENT.EMPLOYEE_ID.eq(employeeId))
                    .and(TASK_ASSIGNMENT.ASSIGNED_AT.greaterOrEqual(month.atDay(1).atStartOfDay()
                            .atOffset(nextMonthStart.getOffset())))
                    .and(TASK_ASSIGNMENT.ASSIGNED_AT.lessThan(nextMonthStart))
                    .orderBy(TASK_ASSIGNMENT.ASSIGNED_AT.asc())
                    .fetch(r -> new AssignmentRecord(
                            r.getId(),
                            r.getDueDate(),
                            r.getStatus().getLiteral(),
                            r.getAssignedAt().toLocalDateTime(),
                            r.getProposedBy().getLiteral(),
                            r.getDoneAt()));

            Map<UUID, LocalDate> lastRunByTask = dsl.select(
                            DAILY_TASK_COMPLETION.TASK_DEFINITION_ID,
                            DSL.max(DAILY_RECORD.WORK_DATE))
                    .from(DAILY_TASK_COMPLETION)
                    .join(DAILY_RECORD).on(DAILY_RECORD.ID.eq(DAILY_TASK_COMPLETION.DAILY_RECORD_ID))
                    .where(DAILY_RECORD.CLINIC_ID.eq(clinicId))
                    .and(DAILY_RECORD.EMPLOYEE_ID.eq(employeeId))
                    .and(DAILY_TASK_COMPLETION.DONE.isTrue())
                    .and(DAILY_TASK_COMPLETION.REVIEW_STATUS.eq(TaskReviewStatus.approved))
                    .groupBy(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID)
                    .fetchMap(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID, DSL.max(DAILY_RECORD.WORK_DATE));

            BigDecimal volumeActual = dsl.select(OPERATIONS_VOLUME.AMOUNT)
                    .from(OPERATIONS_VOLUME)
                    .where(OPERATIONS_VOLUME.CLINIC_ID.eq(clinicId))
                    .and(OPERATIONS_VOLUME.PERIOD_MONTH.eq(month.atDay(1)))
                    .fetchOne(OPERATIONS_VOLUME.AMOUNT);

            return new MonthData(tasks, completions, loggedDates, attendance, assignments, lastRunByTask,
                    volumeActual);
        });
    }
}