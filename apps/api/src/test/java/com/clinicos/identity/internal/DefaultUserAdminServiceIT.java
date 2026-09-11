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
import com.clinicos.identity.api.UserAdminService.UserCreateRequest;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.identity.api.UserAdminService.UserValidationException;
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
        assertThat(users.get(0).employeeId()).isNull();
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
        userAdminService.create(clinicA, new UserCreateRequest(
                uname, "أول", null, "hash"));

        assertThatThrownBy(() -> userAdminService.create(clinicA,
                new UserCreateRequest(uname, "ثاني", null, "hash")))
                .isInstanceOf(UserValidationException.class)
                .satisfies(e -> assertThat(((UserValidationException) e).fieldErrors()).containsKey("username"));
    }

    @Test
    void createDuplicateBlankEmailLeavesConnectionUsable() {
        TenantContext.set(clinicA);
        String empSuffix = "emp-" + UUID.randomUUID();
        userAdminService.create(clinicA, new UserCreateRequest(
                empSuffix, "أول", "", "hash"));

        assertThatThrownBy(() -> userAdminService.create(clinicA,
                new UserCreateRequest("dup-email-" + UUID.randomUUID(), "ثاني", "", "hash")))
                .isInstanceOf(UserValidationException.class)
                .satisfies(e -> assertThat(((UserValidationException) e).fieldErrors()).containsKey("email"));

        userAdminService.create(clinicA, new UserCreateRequest(
                "after-" + UUID.randomUUID(), "بعد الخطأ", null, "hash"));
        assertThat(userAdminService.list(clinicA)).hasSize(2);
    }

    @Test
    void createDuplicateNullEmailSucceedsBothUsers() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new UserCreateRequest(
                "null-email-1-" + UUID.randomUUID(), "أول", null, "hash"));
        userAdminService.create(clinicA, new UserCreateRequest(
                "null-email-2-" + UUID.randomUUID(), "ثاني", null, "hash"));

        assertThat(userAdminService.list(clinicA)).hasSize(2);
    }

    @Test
    void suspendThenListShowsSuspended() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "suspend-" + UUID.randomUUID(), "معلق", null, "hash"));
        UUID userId = userAdminService.list(clinicA).get(0).id();

        userAdminService.suspend(clinicA, userId, UUID.randomUUID());

        List<UserSummary> users = userAdminService.list(clinicA);
        assertThat(users.get(0).status()).isEqualTo("suspended");
    }

    @Test
    void suspendSelfThrows() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "self-" + UUID.randomUUID(), "ذاتي", null, "hash"));
        UUID userId = userAdminService.list(clinicA).get(0).id();
        UUID membershipId = userAdminService.list(clinicA).get(0).membershipId();

        assertThatThrownBy(() -> userAdminService.suspend(clinicA, userId, membershipId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("الخاص");
    }

    @Test
    void suspendOwnerThrows() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "owner-" + UUID.randomUUID(), "مالك", null, "hash"));
        UUID userId = userAdminService.list(clinicA).get(0).id();
        UUID membershipId = userAdminService.list(clinicA).get(0).membershipId();
        userAdminService.assignRole(clinicA, membershipId, "owner");

        assertThatThrownBy(() -> userAdminService.suspend(clinicA, userId, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المالك");
    }

    @Test
    void listShowsLinkedEmployeeId() throws Exception {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "emp-" + UUID.randomUUID(), "مربوط", null, "hash"));
        UUID membershipId = userAdminService.list(clinicA).get(0).membershipId();
        UUID employeeId;
        try (Connection connection = superuser()) {
            try (var statement = connection.prepareStatement(
                    "insert into employee (clinic_id, name, staff_role, base_pay, max_incentive) values (?, ?, 'assistant', 5000, 1500) returning id")) {
                statement.setObject(1, clinicA);
                statement.setString(2, "محمود سمير");
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    employeeId = rs.getObject(1, UUID.class);
                }
            }
        }
        userAdminService.linkEmployee(clinicA, membershipId, employeeId);

        assertThat(userAdminService.list(clinicA).get(0).employeeId()).isEqualTo(employeeId);
    }

    @Test
    void reactivateRestoresActive() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "react-" + UUID.randomUUID(), "معاد", null, "hash"));
        UUID userId = userAdminService.list(clinicA).get(0).id();
        userAdminService.suspend(clinicA, userId, UUID.randomUUID());

        userAdminService.reactivate(clinicA, userId);

        List<UserSummary> users = userAdminService.list(clinicA);
        assertThat(users.get(0).status()).isEqualTo("active");
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
