package com.clinicos.ui.nav;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class NavSectionResolverTest {

    @Test
    void managerSeesEmployeeQuickPrepAcademyInventoryAndDashboard() {
        var visible = NavSectionResolver.resolve(
                Set.of("emp", "quick", "ceo", "tasksTab", "acadVerify", "acadEdit", "tray"), "manager");

        assertThat(keys(visible)).containsExactly("emp", "myeval", "quick", "prep", "acad", "inv", "ceo");
    }

    @Test
    void tasksHiddenWhenDashboardPresent() {
        var withCeo = NavSectionResolver.resolve(Set.of("tasksTab", "ceo"), "manager");
        var withoutCeo = NavSectionResolver.resolve(Set.of("tasksTab"), "manager");

        assertThat(keys(withCeo)).doesNotContain("tasks");
        assertThat(keys(withoutCeo)).contains("tasks");
    }

    @Test
    void myEvaluationHiddenForOwnerAndTasksSupersededByDashboard() {
        var visible = NavSectionResolver.resolve(fullCatalog(), "owner");

        assertThat(keys(visible))
                .containsExactly("quick", "prep", "acad", "inv", "ceo")
                .doesNotContain("myeval", "tasks");
    }

    @Test
    void employeesSectionHiddenForOwnerEvenWithEmpPermission() {
        var visible = NavSectionResolver.resolve(Set.of("emp"), "owner");

        assertThat(keys(visible)).doesNotContain("emp");
    }

    @Test
    void receptionistWithInventoryPortfolioSeesInventoryButNotDashboard() {
        var visible = NavSectionResolver.resolve(Set.of("emp", "orders", "ledger"), "receptionist");

        assertThat(keys(visible)).containsExactly("emp", "myeval", "prep", "inv");
    }

    @Test
    void noPermissionsStillShowsPrepSection() {
        var visible = NavSectionResolver.resolve(Set.of(), "assistant");

        assertThat(keys(visible)).containsExactly("prep");
    }

    @Test
    void academyNeedsAcademyPermission() {
        assertThat(keys(NavSectionResolver.resolve(Set.of("emp"), "assistant"))).doesNotContain("acad");
        assertThat(keys(NavSectionResolver.resolve(Set.of("acadVerify"), "assistant"))).contains("acad");
        assertThat(keys(NavSectionResolver.resolve(Set.of("acadEdit"), "assistant"))).contains("acad");
    }

    @Test
    void inventoryAppearsOnlyWithInventoryAreaCode() {
        assertThat(keys(NavSectionResolver.resolve(Set.of("emp", "quick"), "manager"))).doesNotContain("inv");
        assertThat(keys(NavSectionResolver.resolve(Set.of("tray"), "manager"))).contains("inv");
    }

    @Test
    void sectionByRouteResolvesEveryAuthoredRoute() {
        for (NavSection section : NavSectionResolver.all()) {
            assertThat(NavSectionResolver.sectionByRoute(section.route())).isEqualTo(section);
        }
        assertThat(NavSectionResolver.sectionByRoute("does-not-exist")).isNull();
    }

    @Test
    void authoredNavigationsAreOrderedAndUnique() {
        List<NavSection> all = NavSectionResolver.all();
        assertThat(all).hasSize(8);
        assertThat(all.stream().map(NavSection::route).distinct()).hasSize(all.size());
        assertThat(all.stream().map(NavSection::label)).allSatisfy(label -> assertThat(label).isNotBlank());
    }

    private static List<String> keys(List<NavSection> sections) {
        return sections.stream().map(NavSection::key).toList();
    }

    private static Set<String> fullCatalog() {
        return Set.of("emp", "quick", "ceo", "tasksTab", "acadVerify", "acadEdit",
                "tray", "issue", "procs", "myprocs", "manage", "orders", "receive", "returns",
                "suppliers", "dash", "profit", "analytics", "waste", "doctors", "supAnalysis",
                "received", "itemAnalysis", "approvals", "ledger");
    }
}