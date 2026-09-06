package com.clinicos.ui;

import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.server.VaadinSession;

class HelloViewTest {

    private static Routes routes;

    @BeforeAll
    static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.clinicos");
    }

    @BeforeEach
    void setupVaadin() {
        MockVaadin.setup(routes);
    }

    @AfterEach
    void tearDownVaadin() {
        MockVaadin.tearDown();
    }

    @Test
    void showsFallbackHeadingWhenClinicBoundButNoPermissions() {
        VaadinSession.getCurrent().setAttribute(TenantSessionBinder.SESSION_CLINIC_ID, UUID.randomUUID());

        HelloView view = new HelloView();

        H1 heading = _get(view, H1.class);
        assertThat(heading.getText()).isEqualTo("ClinicOS");
    }

    @Test
    void showsEmptyStateWhenNoClinicInSession() {
        UI.getCurrent().navigate(HelloView.class);

        assertThat(_get(_get(UI.getCurrent(), HelloView.class), Paragraph.class,
                spec -> spec.withClasses("clinicos-empty-state-message")).getText())
                .isEqualTo("لا توجد عيادات مسجلة. لا تملك صلاحية الدخول إلى أي عيادة.");
    }

    @Test
    void rootRouteRendersInsideTheAppShell() {
        UI.getCurrent().navigate(HelloView.class);

        assertThat(_get(MainLayout.class)).isNotNull();
    }

    @Test
    void resolveTargetReturnsFirstSectionWhenNoCookie() {
        assertThat(HelloView.resolveTarget(null, List.of("emp", "quick"), "manager"))
                .isEqualTo("employees");
    }

    @Test
    void resolveTargetReturnsLastSectionIfStillPermitted() {
        assertThat(HelloView.resolveTarget("quick-access", List.of("emp", "quick"), "manager"))
                .isEqualTo("quick-access");
    }

    @Test
    void resolveTargetFallsBackToFirstWhenLastSectionNotPermitted() {
        assertThat(HelloView.resolveTarget("admin-dashboard", List.of("emp", "quick"), "manager"))
                .isEqualTo("employees");
    }

    @Test
    void resolveTargetReturnsNullWhenNoSessionState() {
        assertThat(HelloView.resolveTarget(null, null, null)).isNull();
    }

    @Test
    void resolveTargetLandsOnPrepWhenOnlyUnconditionalSectionVisible() {
        assertThat(HelloView.resolveTarget(null, List.of(), "assistant")).isEqualTo("prep");
    }

    @Test
    void resolveTargetHonoursLegacyRules() {
        String target = HelloView.resolveTarget(null, List.of("emp", "quick"), "owner");
        assertThat(target).isEqualTo("employees");

        target = HelloView.resolveTarget(null, Set.of("emp", "quick", "ceo", "tray").stream().toList(), "manager");
        assertThat(target).isEqualTo("employees");
    }

    @Test
    void navigateToFirstSectionAfterLogin() {
        sessionPermissions("manager", Set.of("emp", "quick"));

        UI.getCurrent().navigate(HelloView.class);

        assertThat(UI.getCurrent().getInternals().getActiveViewLocation().getPath())
                .isEqualTo("employees");
    }

    private static void sessionPermissions(String roleCode, Set<String> codes) {
        VaadinSession session = VaadinSession.getCurrent();
        session.setAttribute(TenantSessionBinder.SESSION_CLINIC_ID, UUID.randomUUID());
        session.setAttribute(TenantSessionBinder.SESSION_ROLE_CODE, roleCode);
        session.setAttribute(TenantSessionBinder.SESSION_PERMISSIONS, List.copyOf(codes));
    }
}