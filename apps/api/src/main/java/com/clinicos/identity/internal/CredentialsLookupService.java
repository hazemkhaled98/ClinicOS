package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.AppUserCredentialsLookupByUsername.APP_USER_CREDENTIALS_LOOKUP_BY_USERNAME;
import static com.clinicos.shared.jooq.tables.AppUserMembershipsLookup.APP_USER_MEMBERSHIPS_LOOKUP;

import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Looks up user credentials and memberships from the database using the
 * V11 SECURITY DEFINER functions that bypass row-level security, accessible
 * only in auth mode.
 *
 * <p>Callers are responsible for the try/finally structure:
 * {@code TenantContext.enterAuthMode()} before calling these methods,
 * {@code TenantContext.exitAuthMode()} in a finally block afterward.
 */
@Service
public class CredentialsLookupService {

    private final DSLContext dsl;
    private final TransactionTemplate transactionTemplate;

    public CredentialsLookupService(DSLContext dsl, TransactionTemplate transactionTemplate) {
        this.dsl = dsl;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Looks up a user's credentials by username. Returns null if the user does
     * not exist.
     */
    public CredentialsRow credentialsLookupByUsername(String username) {
        return transactionTemplate.execute(status -> dsl.selectFrom(
                APP_USER_CREDENTIALS_LOOKUP_BY_USERNAME.call(citext(username)))
                .fetchOne(record -> new CredentialsRow(
                        record.getId(), record.getPasswordHash(), record.getStatus())));
    }

    static Field<String> citext(String value) {
        return DSL.field("?::citext", String.class, value);
    }

    /**
     * Checks whether the user has at least one active membership in any clinic.
     * The app_user_memberships_lookup function only returns active memberships,
     * so we just need to check if any rows are returned.
     * Returns false if the user has no active memberships.
     */
    public boolean hasActiveMembership(UUID userId) {
        return transactionTemplate.execute(status -> dsl.fetchExists(
                APP_USER_MEMBERSHIPS_LOOKUP.call(userId)));
    }

    public record CredentialsRow(UUID id, String passwordHash, String status) {
    }
}