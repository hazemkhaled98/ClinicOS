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
    private UUID actorA;
    private UUID actorB;

    @BeforeEach
    void seedClinics() throws Exception {
        try (var connection = superuser()) {
            clinicA = TestFixtures.insertClinic(connection, "Clinic A", "clinic-a-" + UUID.randomUUID());
            clinicB = TestFixtures.insertClinic(connection, "Clinic B", "clinic-b-" + UUID.randomUUID());
            actorA = TestFixtures.actorMembership(connection, clinicA);
            actorB = TestFixtures.actorMembership(connection, clinicB);
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
                "ahmed-" + UUID.randomUUID(), "أحمد", null, "hash123"), actorA);

        List<UserSummary> users = userAdminService.list(clinicA);

        assertThat(users).hasSize(2);
        assertThat(users).filteredOn(u -> u.username().startsWith("ahmed-")).singleElement()
                .satisfies(u -> {
                    assertThat(u.fullName()).isEqualTo("أحمد");
                    assertThat(u.employeeId()).isNull();
                });
    }

    @Test
    void listFiltersByClinic() {
        TenantContext.set(clinicA);
        String uname = "clinicA-" + UUID.randomUUID();
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                uname, "مستخدم أ", null, "hash"), actorA);
        TenantContext.set(clinicB);
        userAdminService.create(clinicB, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "clinicB-" + UUID.randomUUID(), "مستخدم ب", null, "hash"), actorB);

        TenantContext.set(clinicA);
        List<UserSummary> aUsers = userAdminService.list(clinicA);
        TenantContext.set(clinicB);
        List<UserSummary> bUsers = userAdminService.list(clinicB);

        assertThat(aUsers).hasSize(2);
        assertThat(aUsers).filteredOn(u -> u.username().equals(uname)).singleElement()
                .satisfies(u -> assertThat(u.fullName()).isEqualTo("مستخدم أ"));
        assertThat(bUsers).hasSize(2);
    }

    @Test
    void createDuplicateUsernameThrows() {
        TenantContext.set(clinicA);
        String uname = "dup-" + UUID.randomUUID();
        userAdminService.create(clinicA, new UserCreateRequest(
                uname, "أول", null, "hash"), actorA);

        assertThatThrownBy(() -> userAdminService.create(clinicA,
                new UserCreateRequest(uname, "ثاني", null, "hash"), actorA))
                .isInstanceOf(UserValidationException.class)
                .satisfies(e -> assertThat(((UserValidationException) e).fieldErrors()).containsKey("username"));
    }

    @Test
    void createDuplicateBlankEmailLeavesConnectionUsable() {
        TenantContext.set(clinicA);
        String empSuffix = "emp-" + UUID.randomUUID();
        userAdminService.create(clinicA, new UserCreateRequest(
                empSuffix, "أول", "", "hash"), actorA);

        assertThatThrownBy(() -> userAdminService.create(clinicA,
                new UserCreateRequest("dup-email-" + UUID.randomUUID(), "ثاني", "", "hash"), actorA))
                .isInstanceOf(UserValidationException.class)
                .satisfies(e -> assertThat(((UserValidationException) e).fieldErrors()).containsKey("email"));

        userAdminService.create(clinicA, new UserCreateRequest(
                "after-" + UUID.randomUUID(), "بعد الخطأ", null, "hash"), actorA);
        assertThat(userAdminService.list(clinicA)).hasSize(3);
    }

    @Test
    void createDuplicateNullEmailSucceedsBothUsers() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new UserCreateRequest(
                "null-email-1-" + UUID.randomUUID(), "أول", null, "hash"), actorA);
        userAdminService.create(clinicA, new UserCreateRequest(
                "null-email-2-" + UUID.randomUUID(), "ثاني", null, "hash"), actorA);

        assertThat(userAdminService.list(clinicA)).hasSize(3);
    }

    @Test
    void suspendThenListShowsSuspended() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "suspend-actor");
        setRoleDirect(clinicA, actor.membershipId(), "manager");
        UserSummary target = createUser(clinicA, "suspend-target");
        setRoleDirect(clinicA, target.membershipId(), "assistant");

        userAdminService.suspend(clinicA, target.id(), actor.membershipId());

        List<UserSummary> users = userAdminService.list(clinicA);
        assertThat(users).filteredOn(u -> u.id().equals(target.id())).singleElement()
                .extracting(UserSummary::status).isEqualTo("suspended");
    }

    @Test
    void suspendSelfThrows() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "self-" + UUID.randomUUID(), "ذاتي", null, "hash"), actorA);
        UUID userId = userAdminService.list(clinicA).get(0).id();
        UUID membershipId = userAdminService.list(clinicA).get(0).membershipId();

        assertThatThrownBy(() -> userAdminService.suspend(clinicA, userId, membershipId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("الخاص");
    }

    @Test
    void suspendOwnerThrows() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "owner-test-actor");
        setRoleDirect(clinicA, actor.membershipId(), "manager");
        UserSummary target = createUser(clinicA, "owner-test-target");
        setRoleDirect(clinicA, target.membershipId(), "owner");

        assertThatThrownBy(() -> userAdminService.suspend(clinicA, target.id(), actor.membershipId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المالك");
    }

    @Test
    void suspendCrossClinicThrowsAndLeavesClinicAUnaffected() {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "cross-" + UUID.randomUUID(), "عبر العيادات", null, "hash"), actorA);
        UUID userId = userAdminService.list(clinicA).get(0).id();

        TenantContext.set(clinicB);
        assertThatThrownBy(() -> userAdminService.suspend(clinicB, userId, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("غير موجود");

        TenantContext.set(clinicA);
        assertThat(userAdminService.list(clinicA).get(0).status()).isEqualTo("active");
    }

    @Test
    void listShowsLinkedEmployeeId() throws Exception {
        TenantContext.set(clinicA);
        userAdminService.create(clinicA, new com.clinicos.identity.api.UserAdminService.UserCreateRequest(
                "emp-" + UUID.randomUUID(), "مربوط", null, "hash"), actorA);
        UUID membershipId = userAdminService.list(clinicA).get(0).membershipId();
        UUID employeeId;
        try (Connection connection = superuser()) {
            try (var statement = connection.prepareStatement(
                    "insert into employee (clinic_id, name, base_pay, max_incentive) values (?, ?, 5000, 1500) returning id")) {
                statement.setObject(1, clinicA);
                statement.setString(2, "محمود سمير");
                try (var rs = statement.executeQuery()) {
                    rs.next();
                    employeeId = rs.getObject(1, UUID.class);
                }
            }
        }
        userAdminService.linkEmployee(clinicA, membershipId, employeeId, actorA);

        assertThat(userAdminService.list(clinicA).get(0).employeeId()).isEqualTo(employeeId);
    }

    @Test
    void reactivateRestoresActive() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "react-actor");
        setRoleDirect(clinicA, actor.membershipId(), "manager");
        UserSummary target = createUser(clinicA, "react-target");
        setRoleDirect(clinicA, target.membershipId(), "assistant");
        userAdminService.suspend(clinicA, target.id(), actor.membershipId());

        userAdminService.reactivate(clinicA, target.id(), actor.membershipId());

        List<UserSummary> users = userAdminService.list(clinicA);
        assertThat(users).filteredOn(u -> u.id().equals(target.id())).singleElement()
                .extracting(UserSummary::status).isEqualTo("active");
    }

    @Test
    void managerCannotAssignManagerRole() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "hier-actor");
        setRoleDirect(clinicA, actor.membershipId(), "manager");
        UserSummary target = createUser(clinicA, "hier-target");

        assertThatThrownBy(() -> userAdminService.assignRole(clinicA, target.membershipId(), "manager", actor.membershipId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");
    }

    @Test
    void managerCannotDemoteSelf() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "self-demote");
        setRoleDirect(clinicA, actor.membershipId(), "manager");

        assertThatThrownBy(() -> userAdminService.assignRole(clinicA, actor.membershipId(), "assistant", actor.membershipId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");
    }

    @Test
    void managerCanAssignAssistantToReceptionist() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "assign-actor");
        setRoleDirect(clinicA, actor.membershipId(), "manager");
        UserSummary target = createUser(clinicA, "assign-target");

        userAdminService.assignRole(clinicA, target.membershipId(), "assistant", actor.membershipId());

        assertThat(userAdminService.list(clinicA))
                .filteredOn(u -> u.id().equals(target.id())).singleElement()
                .extracting(UserSummary::roleCode).isEqualTo("assistant");
    }

    @Test
    void ownerCanAssignManagerRole() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "owner-actor");
        setRoleDirect(clinicA, actor.membershipId(), "owner");
        UserSummary target = createUser(clinicA, "promote-target");

        userAdminService.assignRole(clinicA, target.membershipId(), "manager", actor.membershipId());

        assertThat(userAdminService.list(clinicA))
                .filteredOn(u -> u.id().equals(target.id())).singleElement()
                .extracting(UserSummary::roleCode).isEqualTo("manager");
    }

    @Test
    void ownerRoleIsNeverAssignable() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "owner-grant-actor");
        setRoleDirect(clinicA, actor.membershipId(), "owner");
        UserSummary target = createUser(clinicA, "owner-grant-target");

        assertThatThrownBy(() -> userAdminService.assignRole(clinicA, target.membershipId(), "owner", actor.membershipId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("المالك");
    }

    @Test
    void managerCannotSuspendOrReactivateManager() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "mgr-act");
        setRoleDirect(clinicA, actor.membershipId(), "manager");
        UserSummary target = createUser(clinicA, "mgr-tgt");
        setRoleDirect(clinicA, target.membershipId(), "manager");
        UserSummary staff = createUser(clinicA, "staff-tgt");
        setRoleDirect(clinicA, staff.membershipId(), "assistant");

        assertThatThrownBy(() -> userAdminService.suspend(clinicA, target.id(), actor.membershipId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");
        assertThatThrownBy(() -> userAdminService.reactivate(clinicA, target.id(), actor.membershipId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");

        userAdminService.suspend(clinicA, staff.id(), actor.membershipId());
        assertThat(userAdminService.list(clinicA))
                .filteredOn(u -> u.id().equals(staff.id())).singleElement()
                .extracting(UserSummary::status).isEqualTo("suspended");
    }

    @Test
    void managerCannotChangeManagerOrOwnerPasswordButOwnAndLowerWork() throws Exception {
        TenantContext.set(clinicA);
        UserSummary actor = createUser(clinicA, "pwd-actor");
        setRoleDirect(clinicA, actor.membershipId(), "manager");
        UserSummary peer = createUser(clinicA, "pwd-peer");
        setRoleDirect(clinicA, peer.membershipId(), "manager");
        UserSummary owner = createUser(clinicA, "pwd-owner");
        setRoleDirect(clinicA, owner.membershipId(), "owner");
        UserSummary staff = createUser(clinicA, "pwd-staff");
        setRoleDirect(clinicA, staff.membershipId(), "assistant");

        assertThatThrownBy(() -> userAdminService.changePassword(clinicA, peer.id(), "hash-x", actor.membershipId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");
        assertThatThrownBy(() -> userAdminService.changePassword(clinicA, owner.id(), "hash-x", actor.membershipId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("أعلى أو مساوٍ");

        userAdminService.changePassword(clinicA, actor.id(), "hash-self", actor.membershipId());
        userAdminService.changePassword(clinicA, staff.id(), "hash-staff", actor.membershipId());
    }

    @Test
    void ownerCanChangeAnyPassword() throws Exception {
        TenantContext.set(clinicA);
        UserSummary owner = createUser(clinicA, "pwd-owner-actor");
        setRoleDirect(clinicA, owner.membershipId(), "owner");
        UserSummary manager = createUser(clinicA, "pwd-mgr-target");
        setRoleDirect(clinicA, manager.membershipId(), "manager");

        userAdminService.changePassword(clinicA, manager.id(), "hash-new", owner.membershipId());
    }

    private UserSummary createUser(UUID clinicId, String suffix) {
        return userAdminService.create(clinicId, new UserCreateRequest(
                suffix + "-" + UUID.randomUUID(), "مستخدم", null, "hash"), actorOf(clinicId));
    }

    private UUID actorOf(UUID clinicId) {
        return clinicId.equals(clinicB) ? actorB : actorA;
    }

    private static void setRoleDirect(UUID clinicId, UUID membershipId, String roleCode) throws Exception {
        try (Connection connection = superuser()) {
            try (var statement = connection.prepareStatement(
                    "update membership m set role_id = r.id from role r where r.code = ? and m.id = ? and m.clinic_id = ?")) {
                statement.setString(1, roleCode);
                statement.setObject(2, membershipId);
                statement.setObject(3, clinicId);
                statement.executeUpdate();
            }
        }
    }

    private static Connection superuser() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
