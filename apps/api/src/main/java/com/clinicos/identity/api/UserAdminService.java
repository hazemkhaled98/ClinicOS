package com.clinicos.identity.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface UserAdminService {

    List<UserSummary> list(UUID clinicId);

    UserSummary create(UUID clinicId, UserCreateRequest request, UUID actorMembershipId);

    void changePassword(UUID clinicId, UUID userId, String newPasswordHash, UUID actorMembershipId);

    void suspend(UUID clinicId, UUID userId, UUID actorMembershipId);

    void reactivate(UUID clinicId, UUID userId, UUID actorMembershipId);

    void assignRole(UUID clinicId, UUID membershipId, String roleCode, UUID actorMembershipId);

    void linkEmployee(UUID clinicId, UUID membershipId, UUID employeeId, UUID actorMembershipId);

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

    /**
     * Field-level validation failure (Arabic messages, keyed by field name).
     * Raised before any SQL error is provoked so the caller's transaction
     * stays usable and the failure renders as a field error, not a 500.
     */
    class UserValidationException extends RuntimeException {
        private final Map<String, String> fieldErrors;

        public UserValidationException(Map<String, String> fieldErrors) {
            super(String.join("؛ ", fieldErrors.values()));
            this.fieldErrors = fieldErrors;
        }

        public Map<String, String> fieldErrors() {
            return fieldErrors;
        }
    }
}
