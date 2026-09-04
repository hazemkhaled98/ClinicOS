package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.identity.api.PermissionsService.MembershipAccess;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class JdbcPermissionsServiceIT extends AbstractPostgresIntegrationTest {

    private static final Set<String> FULL_CATALOG = Set.of(
            "emp", "quick", "ceo", "tasksTab", "acadVerify", "acadEdit",
            "tray", "issue", "procs", "myprocs", "manage", "orders", "receive", "returns",
            "suppliers", "dash", "profit", "analytics", "waste", "doctors", "supAnalysis",
            "received", "itemAnalysis", "approvals", "ledger");

    @Autowired
    private JdbcPermissionsService permissionsService;

    private UUID clinicA;
    private UUID clinicB;
    private UUID managerMembership;
    private UUID ownerMembership;
    private UUID receptionistMembership;

    @BeforeEach
    void seedClinicAndMemberships() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            clinicA = insertClinic(connection);
            clinicB = insertClinic(connection);
            managerMembership = insertMembership(connection, clinicA, insertUser(connection), "manager");
            ownerMembership = insertMembership(connection, clinicA, insertUser(connection), "owner");
            receptionistMembership = insertMembership(connection, clinicA, insertUser(connection), "receptionist");
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void grantAndRevokeOverrideRoleDefaults() throws Exception {
        TenantContext.set(clinicA);
        grantPermission(managerMembership, "procs", true);
        grantPermission(managerMembership, "ledger", false);

        MembershipAccess access = permissionsService.accessFor(managerMembership);

        assertThat(access.roleCode()).isEqualTo("manager");
        assertThat(access.permissionCodes()).contains("procs");
        assertThat(access.permissionCodes()).doesNotContain("ledger");
        assertThat(access.permissionCodes()).hasSize(FULL_CATALOG.size() - 1);
    }

    @Test
    void extraGrantOutsideRoleDefaultsIsAdded() throws Exception {
        TenantContext.set(clinicA);
        grantPermission(receptionistMembership, "ceo", true);

        MembershipAccess access = permissionsService.accessFor(receptionistMembership);

        assertThat(access.roleCode()).isEqualTo("receptionist");
        assertThat(access.permissionCodes())
                .contains("emp", "orders", "receive", "returns", "suppliers", "ledger", "ceo");
    }

    @Test
    void ownerKeepsEverythingEvenWhenRevoked() throws Exception {
        TenantContext.set(clinicA);
        grantPermission(ownerMembership, "emp", false);
        grantPermission(ownerMembership, "ledger", false);

        MembershipAccess access = permissionsService.accessFor(ownerMembership);

        assertThat(access.permissionCodes()).isEqualTo(FULL_CATALOG);
    }

    @Test
    void unknownMembershipIsRejected() {
        TenantContext.set(clinicA);

        try {
            permissionsService.accessFor(UUID.randomUUID());
            throw new AssertionError("Expected IllegalArgumentException for unknown membership");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("Unknown membership");
        }
    }

    @Test
    void membershipFromAnotherClinicIsInvisibleToBoundTenant() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            UUID otherMembership = insertMembership(connection, clinicB, insertUser(connection), "owner");
            TenantContext.set(clinicA);

            try {
                permissionsService.accessFor(otherMembership);
                throw new AssertionError("Expected IllegalArgumentException for invisible cross-tenant membership");
            } catch (IllegalArgumentException expected) {
                assertThat(expected.getMessage()).contains("Unknown membership");
            }
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

    private UUID insertMembership(Connection connection, UUID clinic, UUID user, String roleCode) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(
                "insert into membership (clinic_id, user_id, role_id, status) select ?, ?, id, 'active' from role where code = ? returning id")) {
            statement.setObject(1, clinic);
            statement.setObject(2, user);
            statement.setString(3, roleCode);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return (UUID) resultSet.getObject(1);
            }
        }
    }

    private void grantPermission(UUID membershipId, String code, boolean granted) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "insert into membership_permission (membership_id, permission_id, granted) select ?, id, ? from permission where code = ?")) {
                statement.setObject(1, membershipId);
                statement.setBoolean(2, granted);
                statement.setString(3, code);
                statement.execute();
            }
        }
    }
}