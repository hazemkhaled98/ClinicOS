package com.clinicos.staff.api;

import java.util.List;
import java.util.UUID;

public interface TaskDefinitionService {

    List<TaskDefinition> list(UUID clinicId);

    TaskDefinition create(UUID clinicId, TaskDefinitionRequest request);

    TaskDefinition update(UUID clinicId, UUID taskId, TaskDefinitionRequest request);

    void delete(UUID clinicId, UUID taskId);

    record TaskDefinition(
            UUID id,
            String name,
            String dimension,
            String frequency,
            String roleCode,
            UUID employeeId,
            boolean requiresPhoto,
            Integer everyN,
            String intervalUnit) {
    }

    record TaskDefinitionRequest(
            String name,
            String dimension,
            String frequency,
            String roleCode,
            UUID employeeId,
            boolean requiresPhoto,
            Integer everyN,
            String intervalUnit) {
    }
}
