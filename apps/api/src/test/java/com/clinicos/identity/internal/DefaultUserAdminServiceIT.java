package com.clinicos.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.clinicos.AbstractPostgresIntegrationTest;
import com.clinicos.Application;
import com.clinicos.TestFixtures;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.TenantContext;

@SpringBootTest(classes = Application.class)
class DefaultUserAdminServiceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private DefaultUserAdminService userAdminService;

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
    void createThenListReturnsUser() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "ahmed-" + UUID.randomUUID(), "أحمد", null, "hash123"));

        List<UserSummary> users = userAdminService.list(clinicA);

        assertThat(users).hasSize(1);
        assertThat(users.get(0).username()).startsWith("ahmed-");
        assertThat(users.get(0).fullName()).isEqualTo("أحمد");
    }

    @Test
    void listFiltersByClinic() {
        TenantContext.set(clinicA);
        String uname = "clinicA-" + UUID.randomUUID();
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                uname, "مستخدم أ", null, "hash"));
        TenantContext.set(clinicB);
        userAdminService.create(clinicB, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "clinicB-" + UUID.randomUUID(), "مستخدم ب", null, "hash"));

        TenantContext.set(clinicA);
        List<UserSummary> aUsers = userAdminService.list(clinicA);
        TenantContext.set(clinicB);
        List<UserSummary> bUsers = userAdminService.list(clinicB);

        assertThat(aUsers).hasSize(1);
        assertThat(aUsers.get(0).username()).isEqualTo(uname);
        assertThat(bUsers).hasSize(1);
    }

    @Test
    void createDuplicateUsernameThrows() {
        TenantContext.set(clinicA);
        String uname = "dup-" + UUID.randomUUID();
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                uname, "أول", null, "hash"));

        assertThatThrownBy(() -> userAdminService.create(clinicA,
                new com.clinicos.identity.api.UserAdminService.UserCreateRequest(uname, "ثاني", null, "hash")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void suspendThenListShowsSuspended() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "suspend-" + UUID.randomUUID(), "معلق", null, "hash"));
        UUID userId = userAdminService.list(clinicA).get(0).id();

        userAdminService.suspend(clinicA, userId);

        List<UserSummary> users = userAdminService.list(clinicA);
        assertThat(users.get(0).status()).isEqualTo("suspended");
    }

    @Test
    void reactivateRestoresActive() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "react-" + UUID.randomUUID(), "معاد", null, "hash"));
        UUID userId = userAdminService.list(clinicA).get(0).id();
        userAdminService.suspend(clinicA, userId);

        userAdminService.reactivate(clinicA, userId);

        List<UserSummary> users = userAdminService.list(clinicA);
        assertThat(users.get(0).status()).isEqualTo("active");
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
