package com.clinicos.shared;

import java.util.Optional;
import java.util.UUID;

/**
 * Holds the {@code clinic_id} of the tenant the current thread is acting
 * for. Set once a user has logged in and a clinic has been selected (see
 * UC-001); read by {@link TenantConnectionListener} at the start of every
 * database transaction, which relies on it being set before any business
 * query runs — Postgres row-level security depends on that ordering.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

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
}
