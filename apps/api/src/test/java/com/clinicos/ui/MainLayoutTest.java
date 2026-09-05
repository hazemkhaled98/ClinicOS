package com.clinicos.ui;

import static com.github.mvysny.kaributesting.v10.LocatorJ._click;
import static com.github.mvysny.kaributesting.v10.LocatorJ._find;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.github.mvysny.kaributesting.v10.BasicUtilsKt._fireDomEvent;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.clinicos.identity.api.AuthenticatedUser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.spring.security.AuthenticationContext;

class MainLayoutTest {

    private static Routes routes;

    private AuthenticationContext authenticationContext;

    @BeforeAll
    static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.clinicos");
    }

    @BeforeEach
    void setup() {
        MockVaadin.setup(routes);
        authenticationContext = mock(AuthenticationContext.class);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "ahmed", "hash"), null, List.of()));
    }

    @AfterEach
    void teardown() {
        MockVaadin.tearDown();
        SecurityContextHolder.clearContext();
    }

    @Test
    void topbarShowsBrandAndArabicLongDate() {
        MainLayout layout = new MainLayout(authenticationContext);

        Span brand = _get(layout, Span.class, spec -> spec.withClasses("clinicos-topbar-t2"));
        Span date = _get(layout, Span.class, spec -> spec.withClasses("clinicos-topbar-date"));

        assertThat(brand.getText()).isEqualTo("عيادتي · إدارة الأداء");
        assertThat(date.getText()).isNotBlank();
    }

    @Test
    void drawerShowsBrand() {
        MainLayout layout = new MainLayout(authenticationContext);

        Span brand = _get(layout, Span.class, spec -> spec.withClasses("clinicos-brand"));

        assertThat(brand.getText()).isEqualTo("عيادتي");
    }

    @Test
    void userBlockShowsLoggedInUserAndLogoutTrigger() {
        MainLayout layout = new MainLayout(authenticationContext);

        Span name = _get(layout, Span.class, spec -> spec.withClasses("clinicos-who-name"));
        Span logout = _get(layout, Span.class, spec -> spec.withClasses("clinicos-logout"));

        assertThat(name.getText()).isEqualTo("ahmed");
        assertThat(logout.getText()).isEqualTo("🚪");
    }

    @Test
    void clickingLogoutInvokesAuthenticationContextLogout() {
        MainLayout layout = new MainLayout(authenticationContext);

        Span logout = _get(layout, Span.class, spec -> spec.withClasses("clinicos-logout"));
        _fireDomEvent(logout, "click");

        verify(authenticationContext).logout();
    }

    @Test
    void arabicLongDateFormatIsDayNameDayNumberAndMonthName() {
        String formatted = MainLayout.arabicLongDate(java.time.LocalDate.of(2026, 9, 4));

        assertThat(formatted).isEqualTo("الجمعة 4 سبتمبر");
    }

    @Test
    void navShowsNoSectionsWhenSessionHasNoPermissions() {
        MainLayout layout = new MainLayout(authenticationContext);

        assertThat(navLabels(layout)).isEmpty();
    }

    @Test
    void navFollowsLegacyRulesForManager() {
        sessionPermissions("manager", Set.of("emp", "quick", "orders"));

        MainLayout layout = new MainLayout(authenticationContext);

        assertThat(navLabels(layout)).containsExactly(
                "إدارة الموظفين", "تقييمي", "الوصول السريع", "إعداد الإجراءات", "المخزون");
    }

    @Test
    void ownerSeesFullNavigationExceptMyEvaluationAndTasks() {
        sessionPermissions("owner", fullCatalog());

        MainLayout layout = new MainLayout(authenticationContext);

        assertThat(navLabels(layout)).containsExactly(
                "إدارة الموظفين", "الوصول السريع", "إعداد الإجراءات", "الأكاديمية", "المخزون", "لوحة التحكم");
    }

    @Test
    void clickingNavItemNavigatesToSection() {
        sessionPermissions("manager", Set.of("quick"));

        MainLayout layout = new MainLayout(authenticationContext);

        Button quick = _get(layout, Button.class,
                spec -> spec.withClasses("clinicos-nav-item").withText("الوصول السريع"));
        _click(quick);

        SectionPlaceholderView placeholder = _get(UI.getCurrent(), SectionPlaceholderView.class);
        H1 title = _get(placeholder, H1.class);
        assertThat(title.getText()).isEqualTo("الوصول السريع");
    }

    private static void sessionPermissions(String roleCode, Set<String> codes) {
        VaadinSession session = VaadinSession.getCurrent();
        session.setAttribute(ClinicPickerView.SESSION_ROLE_CODE, roleCode);
        session.setAttribute(ClinicPickerView.SESSION_PERMISSIONS, List.copyOf(codes));
    }

    private static List<String> navLabels(MainLayout layout) {
        return _find(layout, Button.class, spec -> spec.withClasses("clinicos-nav-item"))
                .stream()
                .map(Button::getText)
                .toList();
    }

    private static Set<String> fullCatalog() {
        return Set.of("emp", "quick", "ceo", "tasksTab", "acadVerify", "acadEdit",
                "tray", "issue", "procs", "myprocs", "manage", "orders", "receive", "returns",
                "suppliers", "dash", "profit", "analytics", "waste", "doctors", "supAnalysis",
                "received", "itemAnalysis", "approvals", "ledger");
    }

    @Test
    void postLogoutStateClearsNavigationAccess() {
        sessionPermissions("manager", Set.of("emp", "quick"));
        MainLayout before = new MainLayout(authenticationContext);
        assertThat(navLabels(before)).isNotEmpty();

        VaadinSession session = VaadinSession.getCurrent();
        session.setAttribute(ClinicPickerView.SESSION_CLINIC_ID, null);
        session.setAttribute(ClinicPickerView.SESSION_MEMBERSHIP_ID, null);
        session.setAttribute(ClinicPickerView.SESSION_ROLE_CODE, null);
        session.setAttribute(ClinicPickerView.SESSION_PERMISSIONS, null);
        SecurityContextHolder.clearContext();

        MainLayout after = new MainLayout(authenticationContext);
        assertThat(navLabels(after)).isEmpty();
    }
}