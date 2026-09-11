package com.clinicos.identity.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface UserAdminService {

    List<UserSummary> list(UUID clinicId);

    UserSummary create(UUID clinicId, UserCreateRequest request);

    void changePassword(UUID clinicId, UUID userId, String newPasswordHash);

    void suspend(UUID clinicId, UUID userId, UUID actorMembershipId);

    void reactivate(UUID clinicId, UUID userId);

    void assignRole(UUID clinicId, UUID membershipId, String roleCode);

    void linkEmployee(UUID clinicId, UUID membershipId, UUID employeeId);

    record UserSummary(
            UUID id,
            String username,
            String fullName,
            String email,
            String status,
            String roleCode,
            UUID membershipId,
            UUID employeeId) {
    }

    record UserCreateRequest(
            String username,
            String fullName,
            String email,
            String passwordHash) {
    }
}
