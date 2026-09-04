package com.clinicos.identity.internal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.shared.TenantContext;

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

    private final DataSource dataSource;
    private final TransactionTemplate transactionTemplate;

    public CredentialsLookupService(DataSource dataSource, TransactionTemplate transactionTemplate) {
        this.dataSource = dataSource;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Looks up a user's credentials by username. Returns null if the user does
     * not exist.
     */
    public CredentialsRow credentialsLookupByUsername(String username) {
        return transactionTemplate.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, password_hash, status from app_user_credentials_lookup_by_username(?::citext)")) {
                statement.setString(1, username);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new CredentialsRow(
                                (UUID) resultSet.getObject(1),
                                resultSet.getString(2),
                                resultSet.getString(3));
                    }
                    return null;
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to look up credentials for username: " + username, e);
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
        return transactionTemplate.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection
                    .prepareStatement("select count(*) > 0 from app_user_memberships_lookup(?)")) {
                statement.setObject(1, userId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return resultSet.getBoolean(1);
                }
            } catch (SQLException e) {
                throw new RuntimeException("Failed to check memberships for user: " + userId, e);
            }
        });
    }

    public record CredentialsRow(UUID id, String passwordHash, String status) {
    }
}
