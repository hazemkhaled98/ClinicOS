package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SectionFocusTest {

    @Test
    void remembersLastSectionCookieWhenStillPermitted() {
        assertThat(SectionFocus.resolveTarget("inventory", List.of("emp", "tray"), "manager"))
                .isEqualTo("inventory");
    }

    @Test
    void ceoPermissionRedirectsToAdminDashboard() {
        // ceo beats the saved section and the first-permitted fallback for owners
        assertThat(SectionFocus.resolveTarget("tasks", ownerCodes(), "owner"))
                .isEqualTo("admin-dashboard");
    }

    @Test
    void managerLandsOnEmployeesInsteadOfCeoDashboard() {
        assertThat(SectionFocus.resolveTarget(null, managerCodes(), "manager"))
                .isEqualTo("employees");
    }

    @Test
    void returnsNullWithoutSessionState() {
        assertThat(SectionFocus.resolveTarget(null, null, null)).isNull();
    }

    @Test
    void alwaysFallsBackToPrepWhichIsUnconditional() {
        assertThat(SectionFocus.resolveTarget(null, List.of(), "assistant"))
                .isEqualTo("prep");
    }

    private static List<String> managerCodes() {
        return List.copyOf(Set.of("emp", "quick", "ceo", "tasksTab", "acadVerify",
                "acadEdit", "tray", "issue", "procs"));
    }

    private static List<String> ownerCodes() {
        return List.copyOf(Set.of("emp", "quick", "ceo", "tasksTab", "acadVerify",
                "acadEdit", "tray", "issue", "procs"));
    }
}