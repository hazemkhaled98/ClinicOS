package com.clinicos.identity.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoleHierarchyTest {

    @Test
    @DisplayName("Ladder order is owner > manager > doctor > assistant = receptionist")
    void ladderOrder() {
        assertThat(RoleHierarchy.rankOf("owner"))
                .isGreaterThan(RoleHierarchy.rankOf("manager"))
                .isGreaterThan(RoleHierarchy.rankOf("doctor"));
        assertThat(RoleHierarchy.rankOf("doctor"))
                .isGreaterThan(RoleHierarchy.rankOf("assistant"))
                .isGreaterThan(RoleHierarchy.rankOf("receptionist"));
        assertThat(RoleHierarchy.rankOf("assistant"))
                .isEqualTo(RoleHierarchy.rankOf("receptionist"));
    }

    @Test
    @DisplayName("An unknown role is rejected rather than silently ranked")
    void unknownRoleIsRejected() {
        assertThatThrownBy(() -> RoleHierarchy.rankOf("superuser"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("دور غير معروف");
    }

    @Test
    @DisplayName("Only owner and manager may administer accounts")
    void accountAdministrators() {
        assertThat(RoleHierarchy.canAdministerAccounts("owner")).isTrue();
        assertThat(RoleHierarchy.canAdministerAccounts("manager")).isTrue();
        assertThat(RoleHierarchy.canAdministerAccounts("doctor")).isFalse();
        assertThat(RoleHierarchy.canAdministerAccounts("assistant")).isFalse();
        assertThat(RoleHierarchy.canAdministerAccounts("receptionist")).isFalse();
    }

    @Test
    @DisplayName("Ranking alone never grants account administration to a doctor")
    void doctorOutranksButCannotAdminister() {
        assertThat(RoleHierarchy.rankOf("doctor"))
                .isGreaterThan(RoleHierarchy.rankOf("assistant"));
        assertThat(RoleHierarchy.canAdminister("doctor", "assistant")).isFalse();
        assertThat(RoleHierarchy.canAdminister("doctor", "receptionist")).isFalse();
    }

    @Test
    @DisplayName("Manager administers every strictly lower role, never its own or above")
    void managerScope() {
        assertThat(RoleHierarchy.canAdminister("manager", "doctor")).isTrue();
        assertThat(RoleHierarchy.canAdminister("manager", "assistant")).isTrue();
        assertThat(RoleHierarchy.canAdminister("manager", "receptionist")).isTrue();
        assertThat(RoleHierarchy.canAdminister("manager", "manager")).isFalse();
        assertThat(RoleHierarchy.canAdminister("manager", "owner")).isFalse();
    }

    @Test
    @DisplayName("Owner administers every other role")
    void ownerScope() {
        assertThat(RoleHierarchy.canAdminister("owner", "manager")).isTrue();
        assertThat(RoleHierarchy.canAdminister("owner", "doctor")).isTrue();
        assertThat(RoleHierarchy.canAdminister("owner", "assistant")).isTrue();
        assertThat(RoleHierarchy.canAdminister("owner", "receptionist")).isTrue();
    }
}
