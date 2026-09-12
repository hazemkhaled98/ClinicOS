package com.clinicos.staff.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DailyWorkService {

    List<DailyTask> today(UUID clinicId, UUID employeeId);

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
            String intervalUnit) {
    }
}