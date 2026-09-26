package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.identity.api.RolePermissionService;
import com.clinicos.identity.api.RolePermissionService.RolePermissionRow;
import com.clinicos.shared.NotificationKind;
import com.clinicos.shared.NotificationService;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class DefaultRolePermissionServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultRolePermissionService rolePermissionService;

    @Autowired
    private NotificationService notifications;

    private UUID clinicA;
    private UUID clinicB;

    @BeforeEach
    void seedClinics() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
        }
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    void setThenListPermissions() throws Exception {
        TenantContext.set(clinicA);
        UUID actor = ownerMembership(clinicA);
        UUID ownerRecipient = ownerMembership(clinicA);
        UUID managerObserver = managerMembership(clinicA);
        rolePermissionService.setPermissions(clinicA, actor, "manager", Set.of("emp", "quick"));

        List<RolePermissionRow> rows = rolePermissionService.listForClinic(clinicA);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(RolePermissionRow::roleCode).containsOnly("manager");
        assertThat(rows).extracting(RolePermissionRow::permissionCode).containsExactlyInAnyOrder("emp", "quick");
        var notification = notifications.recent(clinicA, ownerRecipient, 1).getFirst();
        assertThat(notification.kind()).isEqualTo(NotificationKind.USER_ACCESS_CHANGED);
        assertThat(notification.payload()).containsEntry("user", "الدور manager")
                .containsEntry("actor", "Test User");
        assertThat(notifications.recent(clinicA, actor, 20)).isEmpty();
        assertThat(notifications.recent(clinicA, managerObserver, 20)).isEmpty();
    }

    @Test
    void setReplacesExistingPermissions() throws Exception {
        TenantContext.set(clinicA);
        UUID owner = ownerMembership(clinicA);
        rolePermissionService.setPermissions(clinicA, owner, "manager", Set.of("emp", "quick"));
        rolePermissionService.setPermissions(clinicA, owner, "manager", Set.of("dash"));

        List<RolePermissionRow> rows = rolePermissionService.listForClinic(clinicA);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).permissionCode()).isEqualTo("dash");
    }

    @Test
    void permissionsArePerClinic() throws Exception {
        TenantContext.set(clinicA);
        rolePermissionService.setPermissions(clinicA, ownerMembership(clinicA), "manager", Set.of("emp"));
        TenantContext.set(clinicB);
        rolePermissionService.setPermissions(clinicB, ownerMembership(clinicB), "manager", Set.of("dash"));

        TenantContext.set(clinicA);
        List<RolePermissionRow> aRows = rolePermissionService.listForClinic(clinicA);
        TenantContext.set(clinicB);
        List<RolePermissionRow> bRows = rolePermissionService.listForClinic(clinicB);

        assertThat(aRows).hasSize(1);
        assertThat(aRows.get(0).permissionCode()).isEqualTo("emp");
        assertThat(bRows).hasSize(1);
        assertThat(bRows.get(0).permissionCode()).isEqualTo("dash");
    }

    @Test
    void effectiveCodesReturnsPerRole() throws Exception {
        TenantContext.set(clinicA);
        UUID owner = ownerMembership(clinicA);
        rolePermissionService.setPermissions(clinicA, owner, "manager", Set.of("emp", "dash"));
        rolePermissionService.setPermissions(clinicA, owner, "assistant", Set.of("tasksTab"));

        Set<String> managerCodes = rolePermissionService.effectiveCodes(clinicA, "manager");
        Set<String> assistantCodes = rolePermissionService.effectiveCodes(clinicA, "assistant");

        assertThat(managerCodes).containsExactlyInAnyOrder("emp", "dash");
        assertThat(assistantCodes).containsExactlyInAnyOrder("tasksTab");
    }

    @Test
    void managerCanSetLowerRolePermissions() throws Exception {
        TenantContext.set(clinicA);
        rolePermissionService.setPermissions(clinicA, managerMembership(clinicA), "assistant", Set.of("tasksTab"));
        rolePermissionService.setPermissions(clinicA, managerMembership(clinicA), "receptionist", Set.of("orders"));

        List<RolePermissionRow> rows = rolePermissionService.listForClinic(clinicA);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(RolePermissionRow::roleCode).containsExactlyInAnyOrder("assistant", "receptionist");
    }

    @Test
    void managerCannotSetManagerOrOwnerPermissions() throws Exception {
        TenantContext.set(clinicA);
        UUID manager = managerMembership(clinicA);

        assertThatThrownBy(() -> rolePermissionService.setPermissions(clinicA, manager, "manager", Set.of("emp")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");
        assertThatThrownBy(() -> rolePermissionService.setPermissions(clinicA, manager, "owner", Set.of("emp")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");
    }

    @Test
    void staffActorCannotSetAnyRolePermissions() throws Exception {
        TenantContext.set(clinicA);
        UUID staff = staffMembership(clinicA);

        assertThatThrownBy(() -> rolePermissionService.setPermissions(clinicA, staff, "assistant", Set.of("emp")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");
    }

    @Test
    void ownerCanSetOwnerRolePermissions() throws Exception {
        TenantContext.set(clinicA);
        rolePermissionService.setPermissions(clinicA, ownerMembership(clinicA), "owner", Set.of("emp"));

        assertThat(rolePermissionService.effectiveCodes(clinicA, "owner")).containsExactly("emp");
    }

    private UUID ownerMembership(UUID clinicId) throws Exception {
        return membershipWithRole(clinicId, "owner");
    }

    private UUID managerMembership(UUID clinicId) throws Exception {
        return membershipWithRole(clinicId, "manager");
    }

    private UUID staffMembership(UUID clinicId) throws Exception {
        return membershipWithRole(clinicId, "assistant");
    }

    private static UUID membershipWithRole(UUID clinicId, String roleCode) throws Exception {
        try (Connection connection = superuser()) {
            UUID userId = TestFixtures.insertUser(connection, clinicId);
            return TestFixtures.insertMembership(connection, clinicId, userId, roleCode);
        }
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
