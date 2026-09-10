package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class DefaultRolePermissionServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultRolePermissionService rolePermissionService;

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
    void setThenListPermissions() {
        TenantContext.set(clinicA);
        rolePermissionService.setPermissions(clinicA, "manager", Set.of("emp", "quick"));

        List<RolePermissionRow> rows = rolePermissionService.listForClinic(clinicA);

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(RolePermissionRow::roleCode).containsOnly("manager");
        assertThat(rows).extracting(RolePermissionRow::permissionCode).containsExactlyInAnyOrder("emp", "quick");
    }

    @Test
    void setReplacesExistingPermissions() {
        TenantContext.set(clinicA);
        rolePermissionService.setPermissions(clinicA, "manager", Set.of("emp", "quick"));
        rolePermissionService.setPermissions(clinicA, "manager", Set.of("dash"));

        List<RolePermissionRow> rows = rolePermissionService.listForClinic(clinicA);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).permissionCode()).isEqualTo("dash");
    }

    @Test
    void permissionsArePerClinic() {
        TenantContext.set(clinicA);
        rolePermissionService.setPermissions(clinicA, "manager", Set.of("emp"));
        TenantContext.set(clinicB);
        rolePermissionService.setPermissions(clinicB, "manager", Set.of("dash"));

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
    void effectiveCodesReturnsPerRole() {
        TenantContext.set(clinicA);
        rolePermissionService.setPermissions(clinicA, "manager", Set.of("emp", "dash"));
        rolePermissionService.setPermissions(clinicA, "assistant", Set.of("tasksTab"));

        Set<String> managerCodes = rolePermissionService.effectiveCodes(clinicA, "manager");
        Set<String> assistantCodes = rolePermissionService.effectiveCodes(clinicA, "assistant");

        assertThat(managerCodes).containsExactlyInAnyOrder("emp", "dash");
        assertThat(assistantCodes).containsExactlyInAnyOrder("tasksTab");
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
