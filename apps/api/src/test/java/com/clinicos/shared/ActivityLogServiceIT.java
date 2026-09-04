package com.clinicos.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;

@SpringBootTest(classes = Application.class)
class ActivityLogServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private ActivityLogService activityLogService;

    private UUID clinicId;
    private UUID userId;
    private UUID membershipId;

    @BeforeEach
    void seedClinicAndMembership() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            clinicId = insertClinic(connection);
            userId = insertUser(connection);
            membershipId = insertMembership(connection, clinicId, userId);
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void loginActivityWriteSucceedsWithClinicBoundAndIsVisibleToSameTenant() throws Exception {
        TenantContext.set(clinicId);

        activityLogService.log(clinicId, membershipId, "login", "session");

        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            long count = countActivityRowsForClinic(connection, clinicId);
            assertThat(count).isEqualTo(1);
        }
    }

    @Test
    void loginActivityWriteFailsWithoutClinicBound() {
        TenantContext.clear();

        try {
            activityLogService.log(clinicId, membershipId, "login", "session");
            throw new AssertionError("Expected IllegalStateException because no tenant is bound");
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).contains("No tenant bound");
        }
    }

    private long countActivityRowsForClinic(Connection connection, UUID clinic) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "select count(*) from activity_log where clinic_id = ?")) {
            statement.setObject(1, clinic);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private UUID insertClinic(Connection connection) throws Exception {
        UUID id;
        try (PreparedStatement statement = connection
                .prepareStatement("insert into clinic (name, slug) values (?, ?) returning id")) {
            statement.setString(1, "Test Clinic");
            statement.setString(2, "test-clinic-" + UUID.randomUUID());
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

    private UUID insertUser(Connection connection) throws Exception {
        try (PreparedStatement statement = connection
                .prepareStatement("insert into app_user (username, password_hash, status, full_name) values (?, ?, ?, ?) returning id")) {
            statement.setString(1, "user-" + UUID.randomUUID());
            statement.setString(2, "hash");
            statement.setString(3, "active");
            statement.setString(4, "Test User");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return (UUID) resultSet.getObject(1);
            }
        }
    }

    private UUID insertMembership(Connection connection, UUID clinic, UUID user) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into membership (clinic_id, user_id, role_id, status) select ?, ?, id, 'active' from role where code = ? returning id")) {
            statement.setObject(1, clinic);
            statement.setObject(2, user);
            statement.setString(3, "owner");
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return (UUID) resultSet.getObject(1);
            }
        }
    }
}
