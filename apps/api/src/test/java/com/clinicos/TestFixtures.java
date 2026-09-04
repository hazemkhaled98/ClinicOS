package com.clinicos;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

/**
 * Shared row-insert helpers for integration tests that seed clinic/user/
 * membership rows directly over JDBC (auth-mode and pre-tenant scenarios
 * that can't go through the app's normal tenant-bound repositories).
 */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static UUID insertClinic(Connection connection) throws Exception {
        return insertClinic(connection, "Test Clinic", "test-clinic-" + UUID.randomUUID());
    }

    public static UUID insertClinic(Connection connection, String name, String slug) throws Exception {
        UUID id;
        try (PreparedStatement statement = connection
                .prepareStatement("insert into clinic (name, slug) values (?, ?) returning id")) {
            statement.setString(1, name);
            statement.setString(2, slug);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                id = (UUID) resultSet.getObject(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("insert into clinic_settings (clinic_id) values (?)")) {
            statement.setObject(1, id);
            statement.execute();
        }
        return id;
    }

    public static UUID insertUser(Connection connection) throws Exception {
        return insertUser(connection, "user-" + UUID.randomUUID(), "hash", "active", "Test User");
    }

    public static UUID insertUser(Connection connection, String username, String passwordHash, String status) throws Exception {
        return insertUser(connection, username, passwordHash, status, "Test User");
    }

    public static UUID insertUser(Connection connection, String username, String passwordHash, String status, String fullName)
            throws Exception {
        try (PreparedStatement statement = connection
                .prepareStatement("insert into app_user (username, password_hash, status, full_name) values (?, ?, ?, ?) returning id")) {
            statement.setString(1, username);
            statement.setString(2, passwordHash);
            statement.setString(3, status);
            statement.setString(4, fullName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return (UUID) resultSet.getObject(1);
            }
        }
    }

    public static UUID insertUserWithEmail(Connection connection, String username, String email, String passwordHash, String fullName)
            throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into app_user (username, email, password_hash, full_name, status) values (?, ?, ?, ?, 'active') returning id")) {
            statement.setString(1, username);
            statement.setString(2, email);
            statement.setString(3, passwordHash);
            statement.setString(4, fullName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return (UUID) resultSet.getObject(1);
            }
        }
    }

    public static UUID insertMembership(Connection connection, UUID clinicId, UUID userId) throws Exception {
        return insertMembership(connection, clinicId, userId, "owner");
    }

    public static UUID insertMembership(Connection connection, UUID clinicId, UUID userId, String roleCode) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into membership (clinic_id, user_id, role_id, status) select ?, ?, id, 'active' from role where code = ? returning id")) {
            statement.setObject(1, clinicId);
            statement.setObject(2, userId);
            statement.setString(3, roleCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return (UUID) resultSet.getObject(1);
            }
        }
    }
}
