package com.clinicos.identity.internal;

import static com.clinicos.shared.jooq.tables.MembershipPermission.MEMBERSHIP_PERMISSION;
import static com.clinicos.shared.jooq.tables.Permission.PERMISSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.val;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.identity.api.PermissionsService.MembershipAccess;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class PermissionsServiceIT extends AbstractPostgresIntegrationTest {

    private static final Set<String> FULL_CATALOG = Set.of(
            "emp", "quick", "ceo", "tasksTab", "acadVerify", "acadEdit",
            "tray", "issue", "procs", "myprocs", "manage", "orders", "receive", "returns",
            "suppliers", "dash", "profit", "analytics", "waste", "doctors", "supAnalysis",
            "received", "itemAnalysis", "approvals", "ledger");

    @Autowired
    private DefaultPermissionsService permissionsService;

    private UUID clinicA;
    private UUID clinicB;
    private UUID managerMembership;
    private UUID ownerMembership;
    private UUID receptionistMembership;

    @BeforeEach
    void seedClinicAndMemberships() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            clinicA = TestFixtures.insertClinic(connection);
            clinicB = TestFixtures.insertClinic(connection);
            TestFixtures.seedRolePermissionDefaults(connection, clinicA);
            TestFixtures.seedRolePermissionDefaults(connection, clinicB);
            managerMembership = TestFixtures.insertMembership(connection, clinicA, TestFixtures.insertUser(connection, clinicA), "manager");
            ownerMembership = TestFixtures.insertMembership(connection, clinicA, TestFixtures.insertUser(connection, clinicA), "owner");
            receptionistMembership = TestFixtures.insertMembership(connection, clinicA, TestFixtures.insertUser(connection, clinicA), "receptionist");
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
                .contains("emp", "orders", "receive", "returns", "suppliers", "ceo");
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
            UUID otherMembership = TestFixtures.insertMembership(connection, clinicB, TestFixtures.insertUser(connection, clinicB), "owner");
            TenantContext.set(clinicA);

            try {
                permissionsService.accessFor(otherMembership);
                throw new AssertionError("Expected IllegalArgumentException for invisible cross-tenant membership");
            } catch (IllegalArgumentException expected) {
                assertThat(expected.getMessage()).contains("Unknown membership");
            }
        }
    }

    private void grantPermission(UUID membershipId, String code, boolean granted) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            DSL.using(connection, SQLDialect.POSTGRES)
                    .insertInto(MEMBERSHIP_PERMISSION, MEMBERSHIP_PERMISSION.MEMBERSHIP_ID,
                            MEMBERSHIP_PERMISSION.PERMISSION_ID, MEMBERSHIP_PERMISSION.GRANTED)
                    .select(DSL.select(val(membershipId), PERMISSION.ID, val(granted))
                            .from(PERMISSION)
                            .where(PERMISSION.CODE.eq(code)))
                    .execute();
        }
    }
}