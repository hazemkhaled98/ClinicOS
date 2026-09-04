package com.clinicos.identity.internal;

import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public CredentialsLookupService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Looks up a user's credentials by username. Returns null if the user does
     * not exist.
     */
    public CredentialsRow credentialsLookupByUsername(String username) {
        return transactionTemplate.execute(status -> {
            try {
                return jdbcTemplate.queryForObject(
                        "select id, password_hash, status from app_user_credentials_lookup_by_username(?::citext)",
                        (resultSet, rowNum) -> new CredentialsRow(
                                (UUID) resultSet.getObject(1),
                                resultSet.getString(2),
                                resultSet.getString(3)),
                        username);
            } catch (EmptyResultDataAccessException e) {
                return null;
            }
        });
    }

    /**
     * Checks whether the user has at least one active membership in any clinic.
     * The app_user_memberships_lookup function only returns active memberships,
     * so we just need to check if any rows are returned.
     * Returns false if the user has no active memberships.
     */
    public boolean hasActiveMembership(UUID userId) {
        return transactionTemplate.execute(status -> jdbcTemplate.queryForObject(
                "select count(*) > 0 from app_user_memberships_lookup(?)",
                Boolean.class,
                userId));
    }

    public record CredentialsRow(UUID id, String passwordHash, String status) {
    }
}
