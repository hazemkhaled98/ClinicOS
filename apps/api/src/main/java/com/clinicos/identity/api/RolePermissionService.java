package com.clinicos.identity.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface RolePermissionService {

    List<RolePermissionRow> listForClinic(UUID clinicId);

    void setPermissions(UUID clinicId, UUID actorMembershipId, String roleCode, Set<String> permissionCodes);

    Set<String> effectiveCodes(UUID clinicId, String roleCode);

    record RolePermissionRow(String roleCode, String permissionCode) {
    }
}
