package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.DailyRecord.DAILY_RECORD;
import static com.clinicos.shared.jooq.tables.DailyTaskCompletion.DAILY_TASK_COMPLETION;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
import com.clinicos.shared.jooq.enums.TaskReviewStatus;
import com.clinicos.shared.jooq.tables.records.TaskDefinitionRecord;
import com.clinicos.staff.api.DailyWorkService;
import com.clinicos.staff.api.SelfCheckService;

@Service
public class DefaultDailyWorkService implements DailyWorkService {

    private static final Logger log = LoggerFactory.getLogger(DefaultDailyWorkService.class);

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final SelfCheckService selfCheckService;
    private final NotificationService notificationService;

    public DefaultDailyWorkService(DSLContext dsl, TransactionTemplate transactionTemplate,
            SelfCheckService selfCheckService, NotificationService notificationService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.selfCheckService = selfCheckService;
        this.notificationService = notificationService;
    }

    @Override
    public List<DailyTask> today(UUID clinicId, UUID employeeId) {
        return forDate(clinicId, employeeId, LocalDate.now());
    }

    @Override
    public List<CompletionRow> listForMonth(UUID clinicId, UUID employeeId, YearMonth month) {
        return transactionTemplate.execute(status -> dsl.select(
                        DAILY_TASK_COMPLETION.DAILY_RECORD_ID,
                        DAILY_TASK_COMPLETION.TASK_DEFINITION_ID,
                        TASK_DEFINITION.NAME,
                        DAILY_RECORD.WORK_DATE,
                        DAILY_TASK_COMPLETION.REVIEW_STATUS,
                        DAILY_TASK_COMPLETION.REVIEW_REASON,
                        DAILY_TASK_COMPLETION.PHOTO_ID,
                        TASK_DEFINITION.REQUIRES_PHOTO)
                .from(DAILY_TASK_COMPLETION)
                .join(DAILY_RECORD).on(DAILY_RECORD.ID.eq(DAILY_TASK_COMPLETION.DAILY_RECORD_ID))
                .join(TASK_DEFINITION).on(TASK_DEFINITION.ID.eq(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID))
                .where(DAILY_RECORD.CLINIC_ID.eq(clinicId))
                .and(DAILY_RECORD.EMPLOYEE_ID.eq(employeeId))
                .and(DAILY_RECORD.WORK_DATE.ge(month.atDay(1)))
                .and(DAILY_RECORD.WORK_DATE.le(month.atEndOfMonth()))
                .and(DAILY_TASK_COMPLETION.DONE.isTrue())
                .orderBy(DAILY_RECORD.WORK_DATE.asc(), TASK_DEFINITION.NAME.asc())
                .fetch(r -> new CompletionRow(
                        r.getValue(DAILY_TASK_COMPLETION.DAILY_RECORD_ID),
                        r.getValue(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID),
                        r.getValue(TASK_DEFINITION.NAME),
                        r.getValue(DAILY_RECORD.WORK_DATE),
                        r.getValue(DAILY_TASK_COMPLETION.REVIEW_STATUS) == null ? null
                                : r.getValue(DAILY_TASK_COMPLETION.REVIEW_STATUS).getLiteral(),
                        r.getValue(DAILY_TASK_COMPLETION.REVIEW_REASON),
                        r.getValue(DAILY_TASK_COMPLETION.PHOTO_ID),
                        r.getValue(TASK_DEFINITION.REQUIRES_PHOTO))));
    }

    @Override
    public List<DailyTask> forDate(UUID clinicId, UUID employeeId, LocalDate date) {
        return transactionTemplate.execute(status -> {
            String roleCode = resolveRoleCode(clinicId, employeeId);
            if (roleCode == null) {
                log.warn("No role resolved for employee {} in clinic {} -- returning empty daily task list",
                        employeeId, clinicId);
                return List.of();
            }
            UUID dailyRecordId = ensureDailyRecord(clinicId, employeeId, date);
            Field<LocalDate> lastCompleted = DSL.field(
                    DSL.select(DSL.max(DAILY_RECORD.WORK_DATE))
                            .from(DAILY_TASK_COMPLETION)
                            .join(DAILY_RECORD).on(DAILY_RECORD.ID.eq(DAILY_TASK_COMPLETION.DAILY_RECORD_ID))
                            .where(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID.eq(TASK_DEFINITION.ID))
                            .and(DAILY_TASK_COMPLETION.DONE.isTrue())
                            .and(DAILY_RECORD.WORK_DATE.lt(date)));
            return dsl.select(TASK_DEFINITION.fields())
                    .select(DAILY_TASK_COMPLETION.DONE,
                            DAILY_TASK_COMPLETION.COMPLETED_AT,
                            DAILY_TASK_COMPLETION.PHOTO_ID,
                            DAILY_TASK_COMPLETION.REVIEW_STATUS,
                            DAILY_TASK_COMPLETION.REVIEW_REASON,
                            DAILY_TASK_COMPLETION.REVIEWED_AT,
                            lastCompleted)
                    .from(TASK_DEFINITION)
                    .leftJoin(DAILY_TASK_COMPLETION)
                            .on(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID.eq(TASK_DEFINITION.ID)
                                    .and(DAILY_TASK_COMPLETION.DAILY_RECORD_ID.eq(dailyRecordId)))
                    .where(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                    .and(TASK_DEFINITION.ROLE_CODE.eq(roleCode)
                            .or(TASK_DEFINITION.ROLE_CODE.isNull().and(TASK_DEFINITION.EMPLOYEE_ID.isNull()))
                            .or(TASK_DEFINITION.EMPLOYEE_ID.eq(employeeId)))
                    .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                    .orderBy(TASK_DEFINITION.DISPLAY_ORDER.asc(), TASK_DEFINITION.NAME.asc())
                    .fetch(rec -> newDailyTask(rec, lastCompleted));
        });
    }

    @Override
    public void approveReview(UUID clinicId, UUID dailyRecordId, UUID taskDefinitionId, UUID reviewedByMembershipId) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = dsl.update(DAILY_TASK_COMPLETION)
                    .set(DAILY_TASK_COMPLETION.REVIEW_STATUS, TaskReviewStatus.approved)
                    .set(DAILY_TASK_COMPLETION.REVIEW_REASON, (String) null)
                    .set(DAILY_TASK_COMPLETION.REVIEWED_BY, reviewedByMembershipId)
                    .set(DAILY_TASK_COMPLETION.REVIEWED_AT, OffsetDateTime.now())
                    .where(DAILY_TASK_COMPLETION.DAILY_RECORD_ID.eq(dailyRecordId))
                    .and(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID.eq(taskDefinitionId))
                    .and(DAILY_TASK_COMPLETION.DONE.isTrue())
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("المهمة غير مكتملة أو غير موجودة");
            }
            notificationService.notifyEmployee(clinicId, reviewedByMembershipId, employeeOfRecord(clinicId, dailyRecordId),
                    NotificationKind.DAILY_TASK_APPROVED, Map.of("task", taskName(taskDefinitionId)));
        });
    }

    @Override
    public void rejectReview(UUID clinicId, UUID dailyRecordId, UUID taskDefinitionId, UUID reviewedByMembershipId,
            String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("سبب الرفض مطلوب");
        }
        String stripped = reason.strip();
        transactionTemplate.executeWithoutResult(status -> {
            int updated = dsl.update(DAILY_TASK_COMPLETION)
                    .set(DAILY_TASK_COMPLETION.REVIEW_STATUS, TaskReviewStatus.rejected)
                    .set(DAILY_TASK_COMPLETION.REVIEW_REASON, stripped)
                    .set(DAILY_TASK_COMPLETION.REVIEWED_BY, reviewedByMembershipId)
                    .set(DAILY_TASK_COMPLETION.REVIEWED_AT, OffsetDateTime.now())
                    .where(DAILY_TASK_COMPLETION.DAILY_RECORD_ID.eq(dailyRecordId))
                    .and(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID.eq(taskDefinitionId))
                    .and(DAILY_TASK_COMPLETION.DONE.isTrue())
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("المهمة غير مكتملة أو غير موجودة");
            }
            notificationService.notifyEmployee(clinicId, reviewedByMembershipId, employeeOfRecord(clinicId, dailyRecordId),
                    NotificationKind.DAILY_TASK_REJECTED,
                    Map.of("task", taskName(taskDefinitionId), "reason", stripped));
        });
    }

    private UUID employeeOfRecord(UUID clinicId, UUID dailyRecordId) {
        UUID employeeId = dsl.select(DAILY_RECORD.EMPLOYEE_ID)
                .from(DAILY_RECORD)
                .where(DAILY_RECORD.ID.eq(dailyRecordId))
                .and(DAILY_RECORD.CLINIC_ID.eq(clinicId))
                .fetchOne(DAILY_RECORD.EMPLOYEE_ID);
        if (employeeId == null) {
            throw new IllegalArgumentException("سجل العمل اليومي غير موجود");
        }
        return employeeId;
    }

    private String taskName(UUID taskDefinitionId) {
        return dsl.select(TASK_DEFINITION.NAME)
                .from(TASK_DEFINITION)
                .where(TASK_DEFINITION.ID.eq(taskDefinitionId))
                .fetchOne(TASK_DEFINITION.NAME);
    }

    @Override
    public void complete(UUID clinicId, UUID employeeId, UUID taskDefinitionId, UUID photoId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (selfCheckService.today(clinicId, employeeId).checkedInAt() == null) {
                throw new IllegalArgumentException("لازم تسجّل الحضور الأول");
            }
            String roleCode = resolveRoleCode(clinicId, employeeId);
            if (roleCode == null) {
                throw new IllegalArgumentException("المهمة غير موجودة");
            }
            TaskDefinitionRecord task = dsl.selectFrom(TASK_DEFINITION)
                    .where(TASK_DEFINITION.ID.eq(taskDefinitionId))
                    .and(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                    .and(TASK_DEFINITION.ROLE_CODE.eq(roleCode)
                            .or(TASK_DEFINITION.ROLE_CODE.isNull().and(TASK_DEFINITION.EMPLOYEE_ID.isNull()))
                            .or(TASK_DEFINITION.EMPLOYEE_ID.eq(employeeId)))
                    .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                    .fetchOne();
            if (task == null) {
                throw new IllegalArgumentException("المهمة غير موجودة");
            }
            if (task.getRequiresPhoto() && photoId == null) {
                throw new IllegalArgumentException("لازم ترفق صورة لإثبات هذه المهمة");
            }
            UUID dailyRecordId = ensureDailyRecord(clinicId, employeeId, LocalDate.now());
            dsl.insertInto(DAILY_TASK_COMPLETION,
                    DAILY_TASK_COMPLETION.DAILY_RECORD_ID,
                    DAILY_TASK_COMPLETION.TASK_DEFINITION_ID,
                    DAILY_TASK_COMPLETION.DONE,
                    DAILY_TASK_COMPLETION.COMPLETED_AT,
                    DAILY_TASK_COMPLETION.PHOTO_ID)
                    .values(dailyRecordId, taskDefinitionId, true, OffsetDateTime.now(), photoId)
                    .onConflict(DAILY_TASK_COMPLETION.DAILY_RECORD_ID, DAILY_TASK_COMPLETION.TASK_DEFINITION_ID)
                    .doUpdate()
                    .set(DAILY_TASK_COMPLETION.DONE, true)
                    .set(DAILY_TASK_COMPLETION.COMPLETED_AT, OffsetDateTime.now())
                    .set(DAILY_TASK_COMPLETION.PHOTO_ID, photoId)
                    .set(DAILY_TASK_COMPLETION.REVIEW_STATUS, TaskReviewStatus.pending)
                    .set(DAILY_TASK_COMPLETION.REVIEW_REASON, (String) null)
                    .set(DAILY_TASK_COMPLETION.REVIEWED_BY, (UUID) null)
                    .set(DAILY_TASK_COMPLETION.REVIEWED_AT, (OffsetDateTime) null)
                    .execute();
        });
    }

    @Override
    public void uncomplete(UUID clinicId, UUID employeeId, UUID taskDefinitionId) {
        transactionTemplate.executeWithoutResult(status -> {
            String roleCode = resolveRoleCode(clinicId, employeeId);
            if (roleCode == null) {
                throw new IllegalArgumentException("المهمة غير موجودة");
            }
            TaskDefinitionRecord task = dsl.selectFrom(TASK_DEFINITION)
                    .where(TASK_DEFINITION.ID.eq(taskDefinitionId))
                    .and(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                    .and(TASK_DEFINITION.ROLE_CODE.eq(roleCode)
                            .or(TASK_DEFINITION.ROLE_CODE.isNull().and(TASK_DEFINITION.EMPLOYEE_ID.isNull()))
                            .or(TASK_DEFINITION.EMPLOYEE_ID.eq(employeeId)))
                    .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                    .fetchOne();
            if (task == null) {
                throw new IllegalArgumentException("المهمة غير موجودة");
            }
            UUID dailyRecordId = ensureDailyRecord(clinicId, employeeId, LocalDate.now());
            dsl.insertInto(DAILY_TASK_COMPLETION,
                    DAILY_TASK_COMPLETION.DAILY_RECORD_ID,
                    DAILY_TASK_COMPLETION.TASK_DEFINITION_ID,
                    DAILY_TASK_COMPLETION.DONE)
                    .values(dailyRecordId, taskDefinitionId, false)
                    .onConflict(DAILY_TASK_COMPLETION.DAILY_RECORD_ID, DAILY_TASK_COMPLETION.TASK_DEFINITION_ID)
                    .doUpdate()
                    .set(DAILY_TASK_COMPLETION.DONE, false)
                    .set(DAILY_TASK_COMPLETION.COMPLETED_AT, (OffsetDateTime) null)
                    .set(DAILY_TASK_COMPLETION.PHOTO_ID, (UUID) null)
                    .set(DAILY_TASK_COMPLETION.REVIEW_STATUS, TaskReviewStatus.pending)
                    .set(DAILY_TASK_COMPLETION.REVIEW_REASON, (String) null)
                    .set(DAILY_TASK_COMPLETION.REVIEWED_BY, (UUID) null)
                    .set(DAILY_TASK_COMPLETION.REVIEWED_AT, (OffsetDateTime) null)
                    .execute();
        });
    }

    private DailyTask newDailyTask(org.jooq.Record rec, Field<LocalDate> lastCompleted) {
        return new DailyTask(
                rec.getValue(TASK_DEFINITION.ID),
                rec.getValue(TASK_DEFINITION.NAME),
                fromDbDimension(rec.getValue(TASK_DEFINITION.DIMENSION)),
                fromDbFrequency(rec.getValue(TASK_DEFINITION.FREQUENCY)),
                rec.getValue(TASK_DEFINITION.REQUIRES_PHOTO),
                Boolean.TRUE.equals(rec.getValue(DAILY_TASK_COMPLETION.DONE)),
                rec.getValue(DAILY_TASK_COMPLETION.COMPLETED_AT),
                rec.getValue(DAILY_TASK_COMPLETION.PHOTO_ID),
                rec.getValue(lastCompleted),
                rec.getValue(TASK_DEFINITION.EVERY_N),
                rec.getValue(TASK_DEFINITION.INTERVAL_UNIT) == null ? null
                        : rec.getValue(TASK_DEFINITION.INTERVAL_UNIT).getLiteral(),
                rec.getValue(DAILY_TASK_COMPLETION.REVIEW_STATUS) == null ? null
                        : rec.getValue(DAILY_TASK_COMPLETION.REVIEW_STATUS).getLiteral(),
                rec.getValue(DAILY_TASK_COMPLETION.REVIEW_REASON),
                rec.getValue(DAILY_TASK_COMPLETION.REVIEWED_AT));
    }

    private UUID ensureDailyRecord(UUID clinicId, UUID employeeId, LocalDate date) {
        UUID inserted = dsl.insertInto(DAILY_RECORD,
                DAILY_RECORD.ID,
                DAILY_RECORD.CLINIC_ID,
                DAILY_RECORD.EMPLOYEE_ID,
                DAILY_RECORD.WORK_DATE)
                .values(UUID.randomUUID(), clinicId, employeeId, date)
                .onConflict(DAILY_RECORD.EMPLOYEE_ID, DAILY_RECORD.WORK_DATE)
                .doNothing()
                .returning(DAILY_RECORD.ID)
                .fetchOne(DAILY_RECORD.ID);
        if (inserted != null) {
            return inserted;
        }
        return dsl.select(DAILY_RECORD.ID)
                .from(DAILY_RECORD)
                .where(DAILY_RECORD.CLINIC_ID.eq(clinicId))
                .and(DAILY_RECORD.EMPLOYEE_ID.eq(employeeId))
                .and(DAILY_RECORD.WORK_DATE.eq(date))
                .fetchOne(DAILY_RECORD.ID);
    }

    private String resolveRoleCode(UUID clinicId, UUID employeeId) {
        return dsl.select(ROLE.CODE)
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                .fetchOne(ROLE.CODE);
    }

    private static String fromDbDimension(TaskDimension d) {
        return switch (d) {
            case fanni -> "fanni";
            case solooki -> "solooki";
            case ibda3 -> "ibda3";
        };
    }

    private static String fromDbFrequency(TaskFrequency f) {
        return switch (f) {
            case daily -> "daily";
            case weekly -> "weekly";
            case monthly -> "monthly";
            case custom -> "custom";
        };
    }
}
