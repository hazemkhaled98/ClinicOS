package com.clinicos.identity.api;

import java.util.UUID;

/**
 * Provisions a new clinic tenant plus its first owner user in one atomic step
 * (UC-001, self-service sign-up). The only pre-tenant mutation path in the
 * system: {@code app_rw} is DML-revoked on {@code clinic}/{@code app_user}
 * and {@code membership} is RLS-scoped, so the underlying V13
 * {@code SECURITY DEFINER} function is deliberately the sole door for a
 * clinic to come into existence.
 */
public interface SignupService {

    SignupResult signUp(SignupRequest request);

    record SignupRequest(String clinicName, String fullName, String username, String email, String rawPassword) {
    }

    record SignupResult(UUID userId, UUID clinicId, UUID membershipId) {
    }

    /**
     * A field that collides with an existing value (username, email, or the
     * derived clinic slug). Carries the offending field so the UI can render
     * the error inline on the right input.
     */
    class SignupConflictException extends RuntimeException {

        private final Field field;

        public SignupConflictException(Field field, String message) {
            super(message);
            this.field = field;
        }

        public Field getField() {
            return field;
        }

        public enum Field {
            USERNAME,
            EMAIL,
            CLINIC_SLUG
        }
    }
}