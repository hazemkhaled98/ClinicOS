package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.DailyRecord.DAILY_RECORD;
import static com.clinicos.shared.jooq.tables.DailyTaskCompletion.DAILY_TASK_COMPLETION;
import static com.clinicos.shared.jooq.tables.Membership.MEMBERSHIP;
import static com.clinicos.shared.jooq.tables.Role.ROLE;
import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.jooq.enums.StaffRole;
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
import com.clinicos.shared.jooq.tables.records.TaskDefinitionRecord;
import com.clinicos.staff.api.DailyWorkService;
import com.clinicos.staff.api.SelfCheckService;

@Service
public class DefaultDailyWorkService implements DailyWorkService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;
    private final SelfCheckService selfCheckService;

    public DefaultDailyWorkService(DSLContext dsl, TransactionTemplate transactionTemplate,
            SelfCheckService selfCheckService) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
        this.selfCheckService = selfCheckService;
    }

    @Override
    public List<DailyTask> today(UUID clinicId, UUID employeeId) {
        return transactionTemplate.execute(status -> {
            StaffRole role = resolveStaffRole(clinicId, employeeId);
            if (role == null) {
                return List.of();
            }
            LocalDate date = LocalDate.now();
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
                            lastCompleted)
                    .from(TASK_DEFINITION)
                    .leftJoin(DAILY_TASK_COMPLETION)
                            .on(DAILY_TASK_COMPLETION.TASK_DEFINITION_ID.eq(TASK_DEFINITION.ID)
                                    .and(DAILY_TASK_COMPLETION.DAILY_RECORD_ID.eq(dailyRecordId)))
                    .where(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                    .and(TASK_DEFINITION.STAFF_ROLE.eq(role))
                    .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                    .orderBy(TASK_DEFINITION.DISPLAY_ORDER.asc(), TASK_DEFINITION.NAME.asc())
                    .fetch(rec -> newDailyTask(rec, lastCompleted));
        });
    }

    @Override
    public void complete(UUID clinicId, UUID employeeId, UUID taskDefinitionId, UUID photoId) {
        transactionTemplate.executeWithoutResult(status -> {
            if (selfCheckService.today(clinicId, employeeId).checkedInAt() == null) {
                throw new IllegalArgumentException("لازم تسجّل الحضور الأول");
            }
            TaskDefinitionRecord task = dsl.selectFrom(TASK_DEFINITION)
                    .where(TASK_DEFINITION.ID.eq(taskDefinitionId))
                    .and(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
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
                    .execute();
        });
    }

    @Override
    public void uncomplete(UUID clinicId, UUID employeeId, UUID taskDefinitionId) {
        transactionTemplate.executeWithoutResult(status -> {
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
                        : rec.getValue(TASK_DEFINITION.INTERVAL_UNIT).getLiteral());
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

    private StaffRole resolveStaffRole(UUID clinicId, UUID employeeId) {
        String roleCode = dsl.select(ROLE.CODE)
                .from(MEMBERSHIP)
                .join(ROLE).on(ROLE.ID.eq(MEMBERSHIP.ROLE_ID))
                .where(MEMBERSHIP.CLINIC_ID.eq(clinicId))
                .and(MEMBERSHIP.EMPLOYEE_ID.eq(employeeId))
                .fetchOne(ROLE.CODE);
        return switch (roleCode == null ? "" : roleCode) {
            case "assistant" -> StaffRole.assistant;
            case "receptionist" -> StaffRole.receptionist;
            default -> null;
        };
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