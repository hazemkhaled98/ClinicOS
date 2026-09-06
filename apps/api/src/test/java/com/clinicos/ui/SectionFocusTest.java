package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SectionFocusTest {

    @Test
    void remembersLastSectionCookieWhenStillPermitted() {
        assertThat(SectionFocus.resolveTarget("inventory", ownerCodes(), "owner"))
                .isEqualTo("inventory");
    }

    @Test
    void fallsBackToFirstPermittedSectionWhenCookieNotPermitted() {
        // tasks is hidden for owners (dashboard supersedes it)
        assertThat(SectionFocus.resolveTarget("tasks", ownerCodes(), "owner"))
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

    private static List<String> ownerCodes() {
        return List.copyOf(Set.of("emp", "quick", "ceo", "tasksTab", "acadVerify",
                "acadEdit", "tray", "issue", "procs"));
    }
}