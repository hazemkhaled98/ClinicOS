package com.clinicos.shared;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the {@code clinic_id} of the tenant the current thread acts for. Set
 * after a user logs in and selects a clinic (UC-001); read by
 * {@link TenantConnectionListener} at the start of every database
 * transaction, which relies on it being set before any business query runs —
 * Postgres row-level security depends on that ordering.
 *
 * <p><strong>Auth Mode Escape Hatch:</strong> Before clinic selection (during
 * login), {@link #enterAuthMode()} / {@link #exitAuthMode()} allow the login
 * code to query user credentials and clinic memberships without having a
 * clinic bound. In auth mode, {@link TenantConnectionListener} binds the nil
 * UUID (00000000-0000-0000-0000-000000000000) to {@code app.clinic_id},
 * guaranteeing any RLS-scoped table matches zero rows. The only paths to
 * read data in auth mode are the {@code SECURITY DEFINER} functions in V14
 * ({@code app_user_credentials_lookup_by_clinic_username},
 * {@code app_user_memberships_lookup}), which bypass RLS entirely — there is
 * no way to leak another tenant's data through this mechanism.
 *
 * <p>Do NOT call auth mode methods except from the identity/login service.
 * Always use try/finally to ensure {@link #exitAuthMode()} runs even if an
 * exception occurs.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> AUTH_MODE = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(UUID clinicId) {
        CURRENT.set(clinicId);
    }

    public static Optional<UUID> get() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void enterAuthMode() {
        AUTH_MODE.set(true);
    }

    public static void exitAuthMode() {
        AUTH_MODE.remove();
    }

    public static boolean isAuthMode() {
        return Boolean.TRUE.equals(AUTH_MODE.get());
    }
}
