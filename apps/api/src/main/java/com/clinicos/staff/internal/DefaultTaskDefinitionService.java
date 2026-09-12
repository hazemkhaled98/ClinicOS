package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;

import java.util.List;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.staff.api.TaskDefinitionService;
import com.clinicos.staff.api.TaskDefinitionService.TaskDefinition;
import com.clinicos.staff.api.TaskDefinitionService.TaskDefinitionRequest;

@Service
public class DefaultTaskDefinitionService implements TaskDefinitionService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public DefaultTaskDefinitionService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public List<TaskDefinition> list(UUID clinicId) {
        return transactionTemplate.execute(status ->
                dsl.selectFrom(TASK_DEFINITION)
                        .where(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                        .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                        .orderBy(TASK_DEFINITION.DISPLAY_ORDER.asc(), TASK_DEFINITION.NAME.asc())
                        .fetch(this::toTask));
    }

    @Override
    public TaskDefinition create(UUID clinicId, TaskDefinitionRequest request) {
        return transactionTemplate.execute(status -> {
            UUID id = UUID.randomUUID();
            dsl.insertInto(TASK_DEFINITION)
                    .set(TASK_DEFINITION.ID, id)
                    .set(TASK_DEFINITION.CLINIC_ID, clinicId)
                    .set(TASK_DEFINITION.NAME, request.name())
                    .set(TASK_DEFINITION.DIMENSION, toDbDimension(request.dimension()))
                    .set(TASK_DEFINITION.FREQUENCY, toDbFrequency(request.frequency()))
                    .set(TASK_DEFINITION.STAFF_ROLE, toDbStaffRole(request.roleCode()))
                    .execute();
            return new TaskDefinition(id, request.name(), request.dimension(), request.frequency(), request.roleCode());
        });
    }

    @Override
    public TaskDefinition update(UUID clinicId, UUID taskId, TaskDefinitionRequest request) {
        return transactionTemplate.execute(status -> {
            int updated = dsl.update(TASK_DEFINITION)
                    .set(TASK_DEFINITION.NAME, request.name())
                    .set(TASK_DEFINITION.DIMENSION, toDbDimension(request.dimension()))
                    .set(TASK_DEFINITION.FREQUENCY, toDbFrequency(request.frequency()))
                    .set(TASK_DEFINITION.STAFF_ROLE, toDbStaffRole(request.roleCode()))
                    .where(TASK_DEFINITION.ID.eq(taskId))
                    .and(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                    .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("المهمة غير موجودة");
            }
            var record = dsl.selectFrom(TASK_DEFINITION)
                    .where(TASK_DEFINITION.ID.eq(taskId))
                    .and(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                    .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                    .fetchOne();
            if (record == null) {
                throw new IllegalArgumentException("المهمة غير موجودة");
            }
            return toTask(record);
        });
    }

    @Override
    public void delete(UUID clinicId, UUID taskId) {
        transactionTemplate.executeWithoutResult(status -> {
            int updated = dsl.update(TASK_DEFINITION)
                    .set(TASK_DEFINITION.ARCHIVED_AT, java.time.OffsetDateTime.now())
                    .where(TASK_DEFINITION.ID.eq(taskId))
                    .and(TASK_DEFINITION.CLINIC_ID.eq(clinicId))
                    .and(TASK_DEFINITION.ARCHIVED_AT.isNull())
                    .execute();
            if (updated == 0) {
                throw new IllegalArgumentException("المهمة غير موجودة");
            }
        });
    }

    private TaskDefinition toTask(com.clinicos.shared.jooq.tables.records.TaskDefinitionRecord r) {
        return new TaskDefinition(
                r.getId(),
                r.getName(),
                fromDbDimension(r.getDimension()),
                fromDbFrequency(r.getFrequency()),
                fromDbStaffRole(r.getStaffRole()));
    }

    private static com.clinicos.shared.jooq.enums.TaskDimension toDbDimension(String dimension) {
        return switch (dimension) {
            case "fanni" -> com.clinicos.shared.jooq.enums.TaskDimension.fanni;
            case "solooki" -> com.clinicos.shared.jooq.enums.TaskDimension.solooki;
            case "ibda3" -> com.clinicos.shared.jooq.enums.TaskDimension.ibda3;
            default -> throw new IllegalArgumentException("البُعد غير معروف: " + dimension);
        };
    }

    private static String fromDbDimension(com.clinicos.shared.jooq.enums.TaskDimension d) {
        return switch (d) {
            case fanni -> "fanni";
            case solooki -> "solooki";
            case ibda3 -> "ibda3";
        };
    }

    private static com.clinicos.shared.jooq.enums.TaskFrequency toDbFrequency(String frequency) {
        return switch (frequency) {
            case "daily" -> com.clinicos.shared.jooq.enums.TaskFrequency.daily;
            case "weekly" -> com.clinicos.shared.jooq.enums.TaskFrequency.weekly;
            case "monthly" -> com.clinicos.shared.jooq.enums.TaskFrequency.monthly;
            case "custom" -> com.clinicos.shared.jooq.enums.TaskFrequency.custom;
            default -> throw new IllegalArgumentException("التكرار غير معروف: " + frequency);
        };
    }

    private static String fromDbFrequency(com.clinicos.shared.jooq.enums.TaskFrequency f) {
        return switch (f) {
            case daily -> "daily";
            case weekly -> "weekly";
            case monthly -> "monthly";
            case custom -> "custom";
        };
    }

    private static com.clinicos.shared.jooq.enums.StaffRole toDbStaffRole(String roleCode) {
        return switch (roleCode) {
            case "assistant" -> com.clinicos.shared.jooq.enums.StaffRole.assistant;
            case "receptionist" -> com.clinicos.shared.jooq.enums.StaffRole.receptionist;
            default -> throw new IllegalArgumentException("الدور غير معروف: " + roleCode);
        };
    }

    private static String fromDbStaffRole(com.clinicos.shared.jooq.enums.StaffRole r) {
        return switch (r) {
            case assistant -> "assistant";
            case receptionist -> "receptionist";
        };
    }
}
