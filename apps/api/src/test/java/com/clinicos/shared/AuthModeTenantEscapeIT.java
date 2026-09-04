package com.clinicos.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;

@SpringBootTest(classes = Application.class)
class AuthModeTenantEscapeIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID testUserId;
    private UUID testClinicId;
    private String uniqueSuffix;

    @BeforeEach
    void seedTestData() throws Exception {
        uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            testUserId = insertUser(connection, "testuser-" + uniqueSuffix, "test-" + uniqueSuffix + "@example.com", "hashed-password-123", "Test User");
            testClinicId = insertClinic(connection, "Test Clinic " + uniqueSuffix, "test-clinic-" + uniqueSuffix);
            insertMembership(connection, testClinicId, testUserId, "owner");
        }
    }

    @AfterEach
    void cleanup() {
        TenantContext.clear();
        TenantContext.exitAuthMode();
    }

    @Test
    void credentialsLookupByUsernameSucceedsInAuthMode() {
        TenantContext.enterAuthMode();

        String passwordHash = transactionTemplate.execute(status -> {
            Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection
                    .prepareStatement("select password_hash from app_user_credentials_lookup_by_username(?::citext)")) {
                statement.setString(1, "testuser-" + uniqueSuffix);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return resultSet.getString(1);
                    }
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(passwordHash).isEqualTo("hashed-password-123");
    }

    @Test
    void credentialsLookupByUsernameReturnsUserIdAndStatus() {
        TenantContext.enterAuthMode();

        record CredentialRow(UUID id, String passwordHash, String status) {
        }

        CredentialRow credentials = transactionTemplate.execute(status -> {
            Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select id, password_hash, status from app_user_credentials_lookup_by_username(?::citext)")) {
                statement.setString(1, "testuser-" + uniqueSuffix);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new CredentialRow(
                                (UUID) resultSet.getObject(1),
                                resultSet.getString(2),
                                resultSet.getString(3));
                    }
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(credentials).isNotNull();
        assertThat(credentials.id).isEqualTo(testUserId);
        assertThat(credentials.passwordHash).isEqualTo("hashed-password-123");
        assertThat(credentials.status).isEqualTo("active");
    }

    @Test
    void membershipLookupReturnsAllActiveClinicMemberships() {
        TenantContext.enterAuthMode();

        record MembershipRow(UUID clinicId, String clinicName, String roleCode) {
        }

        MembershipRow membership = transactionTemplate.execute(status -> {
            Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection.prepareStatement(
                    "select clinic_id, clinic_name, role_code from app_user_memberships_lookup(?)")) {
                statement.setObject(1, testUserId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (resultSet.next()) {
                        return new MembershipRow(
                                (UUID) resultSet.getObject(1),
                                resultSet.getString(2),
                                resultSet.getString(3));
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        assertThat(membership).isNotNull();
        assertThat(membership.clinicId).isEqualTo(testClinicId);
        assertThat(membership.clinicName).isEqualTo("Test Clinic " + uniqueSuffix);
        assertThat(membership.roleCode).isEqualTo("owner");
    }

    @Test
    void rLSScopedTableReturnsZeroRowsInAuthMode() {
        TenantContext.enterAuthMode();

        long count = transactionTemplate.execute(status -> {
            Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection.prepareStatement("select count(*) from clinic_settings");
                    ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(count).isEqualTo(0);
    }

    @Test
    void exitAuthModeRestoresNormalBehavior() {
        TenantContext.enterAuthMode();
        TenantContext.exitAuthMode();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            Connection connection = org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource);
            try (PreparedStatement statement = connection.prepareStatement("select count(*) from clinic_settings");
                    ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No tenant bound");
    }

    private UUID insertUser(Connection connection, String username, String email, String passwordHash, String fullName)
            throws Exception {
        UUID userId;
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into app_user (username, email, password_hash, full_name, status) values (?, ?, ?, ?, 'active') returning id")) {
            statement.setString(1, username);
            statement.setString(2, email);
            statement.setString(3, passwordHash);
            statement.setString(4, fullName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                userId = (UUID) resultSet.getObject(1);
            }
        }
        return userId;
    }

    private UUID insertClinic(Connection connection, String name, String slug) throws Exception {
        UUID clinicId;
        try (PreparedStatement statement = connection
                .prepareStatement("insert into clinic (name, slug) values (?, ?) returning id")) {
            statement.setString(1, name);
            statement.setString(2, slug);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                clinicId = (UUID) resultSet.getObject(1);
            }
        }
        try (PreparedStatement statement = connection
                .prepareStatement("insert into clinic_settings (clinic_id) values (?)")) {
            statement.setObject(1, clinicId);
            statement.execute();
        }
        return clinicId;
    }

    private void insertMembership(Connection connection, UUID clinicId, UUID userId, String roleCode) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into membership (clinic_id, user_id, role_id, status) select ?, ?, id, 'active' from role where code = ?")) {
            statement.setObject(1, clinicId);
            statement.setObject(2, userId);
            statement.setString(3, roleCode);
            statement.execute();
        }
    }
}
