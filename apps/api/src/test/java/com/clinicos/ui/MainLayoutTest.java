package com.clinicos.ui;

import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.clinicos.identity.api.AuthenticatedUser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.html.Span;

class MainLayoutTest {

    private static Routes routes;

    @BeforeAll
    static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.clinicos");
    }

    @BeforeEach
    void setup() {
        MockVaadin.setup(routes);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        new AuthenticatedUser(UUID.randomUUID(), "ahmed", "hash"), null, List.of()));
    }

    @AfterEach
    void teardown() {
        MockVaadin.tearDown();
        SecurityContextHolder.clearContext();
    }

    @Test
    void topbarShowsBrandAndArabicLongDate() {
        MainLayout layout = new MainLayout();

        Span brand = _get(layout, Span.class, spec -> spec.withClasses("clinicos-topbar-t2"));
        Span date = _get(layout, Span.class, spec -> spec.withClasses("clinicos-topbar-date"));

        assertThat(brand.getText()).isEqualTo("عيادتي · إدارة الأداء");
        assertThat(date.getText()).isNotBlank();
    }

    @Test
    void drawerShowsBrand() {
        MainLayout layout = new MainLayout();

        Span brand = _get(layout, Span.class, spec -> spec.withClasses("clinicos-brand"));

        assertThat(brand.getText()).isEqualTo("عيادتي");
    }

    @Test
    void userBlockShowsLoggedInUserAndLogoutTrigger() {
        MainLayout layout = new MainLayout();

        Span name = _get(layout, Span.class, spec -> spec.withClasses("clinicos-who-name"));
        Span logout = _get(layout, Span.class, spec -> spec.withClasses("clinicos-logout"));

        assertThat(name.getText()).isEqualTo("ahmed");
        assertThat(logout.getText()).isEqualTo("🚪");
    }

    @Test
    void arabicLongDateFormatIsDayNameDayNumberAndMonthName() {
        String formatted = MainLayout.arabicLongDate(java.time.LocalDate.of(2026, 9, 4));

        assertThat(formatted).isEqualTo("الجمعة 4 سبتمبر");
    }
}