package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import com.clinicos.identity.api.SignupService.SignupConflictException;

/**
 * Unit tests for {@link DefaultSignupService#mapConflict}, exercised
 * directly rather than through {@code signUp()}: per-clinic scoping (V14)
 * means no two calls to {@code signUp()} can ever collide on username or
 * email today, so these branches are otherwise untestable through the
 * service's own entry point. {@code mapConflict} touches none of the
 * service's collaborators, so a bare instance (nulls for its constructor
 * args) is enough.
 */
class DefaultSignupServiceMapConflictTest {

    private final DefaultSignupService service = new DefaultSignupService(null, null, null);

    @Test
    void mapsUsernameConstraintViolation() {
        SignupConflictException conflict = service.mapConflict(
                new DuplicateKeyException("duplicate key value violates unique constraint \"app_user_clinic_username_key\""));

        assertThat(conflict.getField()).isEqualTo(SignupConflictException.Field.USERNAME);
    }

    @Test
    void mapsEmailConstraintViolation() {
        SignupConflictException conflict = service.mapConflict(
                new DuplicateKeyException("duplicate key value violates unique constraint \"idx_app_user_email_when_not_null\""));

        assertThat(conflict.getField()).isEqualTo(SignupConflictException.Field.EMAIL);
    }

    @Test
    void mapsClinicSlugConstraintViolation() {
        SignupConflictException conflict = service.mapConflict(
                new DuplicateKeyException("duplicate key value violates unique constraint \"clinic_slug_key\""));

        assertThat(conflict.getField()).isEqualTo(SignupConflictException.Field.CLINIC_SLUG);
    }

    @Test
    void unrecognizedConstraintRethrowsOriginalException() {
        DuplicateKeyException original = new DuplicateKeyException("duplicate key value violates unique constraint \"some_future_constraint\"");

        assertThatThrownBy(() -> service.mapConflict(original))
                .isSameAs(original);
    }

    @Test
    void nullMessageRethrowsWithoutNullPointerException() {
        DuplicateKeyException original = new DuplicateKeyException(null);

        assertThatThrownBy(() -> service.mapConflict(original))
                .isSameAs(original);
    }
}
