package com.clinicos.staff.internal;

import static com.clinicos.shared.jooq.tables.TaskDefinition.TASK_DEFINITION;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.jooq.enums.IntervalUnit;
import com.clinicos.shared.jooq.enums.StaffRole;
import com.clinicos.shared.jooq.enums.TaskDimension;
import com.clinicos.shared.jooq.enums.TaskFrequency;
import com.clinicos.shared.jooq.tables.records.TaskDefinitionRecord;
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
        validate(request);
        return transactionTemplate.execute(status -> {
            UUID id = UUID.randomUUID();
            dsl.insertInto(TASK_DEFINITION)
                    .set(TASK_DEFINITION.ID, id)
                    .set(TASK_DEFINITION.CLINIC_ID, clinicId)
                    .set(TASK_DEFINITION.NAME, request.name())
                    .set(TASK_DEFINITION.DIMENSION, toDbDimension(request.dimension()))
                    .set(TASK_DEFINITION.FREQUENCY, toDbFrequency(request.frequency()))
                    .set(TASK_DEFINITION.STAFF_ROLE, toDbStaffRole(request.roleCode()))
                    .set(TASK_DEFINITION.REQUIRES_PHOTO, request.requiresPhoto())
                    .set(TASK_DEFINITION.EVERY_N, request.everyN())
                    .set(TASK_DEFINITION.INTERVAL_UNIT,
                            request.intervalUnit() == null ? null : IntervalUnit.valueOf(request.intervalUnit()))
                    .execute();
            return new TaskDefinition(id, request.name(), request.dimension(), request.frequency(), request.roleCode(),
                    request.requiresPhoto(), request.everyN(), request.intervalUnit());
        });
    }

    @Override
    public TaskDefinition update(UUID clinicId, UUID taskId, TaskDefinitionRequest request) {
        validate(request);
        return transactionTemplate.execute(status -> {
            int updated = dsl.update(TASK_DEFINITION)
                    .set(TASK_DEFINITION.NAME, request.name())
                    .set(TASK_DEFINITION.DIMENSION, toDbDimension(request.dimension()))
                    .set(TASK_DEFINITION.FREQUENCY, toDbFrequency(request.frequency()))
                    .set(TASK_DEFINITION.STAFF_ROLE, toDbStaffRole(request.roleCode()))
                    .set(TASK_DEFINITION.REQUIRES_PHOTO, request.requiresPhoto())
                    .set(TASK_DEFINITION.EVERY_N, request.everyN())
                    .set(TASK_DEFINITION.INTERVAL_UNIT,
                            request.intervalUnit() == null ? null : IntervalUnit.valueOf(request.intervalUnit()))
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

    private TaskDefinition toTask(TaskDefinitionRecord r) {
        return new TaskDefinition(
                r.getId(),
                r.getName(),
                fromDbDimension(r.getDimension()),
                fromDbFrequency(r.getFrequency()),
                fromDbStaffRole(r.getStaffRole()),
                r.getRequiresPhoto(),
                r.getEveryN(),
                r.getIntervalUnit() == null ? null : r.getIntervalUnit().getLiteral());
    }

    private void validate(TaskDefinitionRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new IllegalArgumentException("اسم المهمة مطلوب");
        }
        if ("custom".equals(request.frequency())) {
            if (request.everyN() == null || request.everyN() < 1) {
                throw new IllegalArgumentException("التكرار المخصص يتطلب رقم تكرار صحيح موجب");
            }
            if (request.intervalUnit() == null) {
                throw new IllegalArgumentException("التكرار المخصص يتطلب وحدة زمنية");
            }
            if (!Set.of("day", "week", "month").contains(request.intervalUnit())) {
                throw new IllegalArgumentException("الوحدة الزمنية غير معروفة: " + request.intervalUnit());
            }
        } else if (request.everyN() != null || request.intervalUnit() != null) {
            throw new IllegalArgumentException("العدد والوحدة الزمنية خاصان بالتكرار المخصص فقط");
        }
    }

    private static TaskDimension toDbDimension(String dimension) {
        return switch (dimension) {
            case "fanni" -> TaskDimension.fanni;
            case "solooki" -> TaskDimension.solooki;
            case "ibda3" -> TaskDimension.ibda3;
            default -> throw new IllegalArgumentException("البُعد غير معروف: " + dimension);
        };
    }

    private static String fromDbDimension(TaskDimension d) {
        return switch (d) {
            case fanni -> "fanni";
            case solooki -> "solooki";
            case ibda3 -> "ibda3";
        };
    }

    private static TaskFrequency toDbFrequency(String frequency) {
        return switch (frequency) {
            case "daily" -> TaskFrequency.daily;
            case "weekly" -> TaskFrequency.weekly;
            case "monthly" -> TaskFrequency.monthly;
            case "custom" -> TaskFrequency.custom;
            default -> throw new IllegalArgumentException("التكرار غير معروف: " + frequency);
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

    private static StaffRole toDbStaffRole(String roleCode) {
        return switch (roleCode) {
            case "assistant" -> StaffRole.assistant;
            case "receptionist" -> StaffRole.receptionist;
            default -> throw new IllegalArgumentException("الدور غير معروف: " + roleCode);
        };
    }

    private static String fromDbStaffRole(StaffRole r) {
        return switch (r) {
            case assistant -> "assistant";
            case receptionist -> "receptionist";
        };
    }
}
