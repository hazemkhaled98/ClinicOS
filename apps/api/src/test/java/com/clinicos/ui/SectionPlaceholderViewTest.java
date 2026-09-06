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
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Paragraph;

class SectionPlaceholderViewTest {

    private static Routes routes;

    @BeforeAll
    static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.clinicos");
    }

    @BeforeEach
    void setup() {
        MockVaadin.setup(routes);
        AuthenticatedUser testUser = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "testuser", "hash");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(testUser, null, List.of()));
    }

    @AfterEach
    void teardown() {
        MockVaadin.tearDown();
        SecurityContextHolder.clearContext();
    }

    @Test
    void showsSubtitleForEmployeesSection() {
        UI.getCurrent().navigate("employees");

        SectionPlaceholderView view = _get(SectionPlaceholderView.class);
        Paragraph subtitle = _get(view, Paragraph.class,
                spec -> spec.withClasses("clinicos-section-sub"));

        assertThat(subtitle.getText()).isEqualTo("إدارة ملفات الموظفين وصلاحياتهم");
    }

    @Test
    void showsEmptyStateMessageForEmployeesSection() {
        UI.getCurrent().navigate("employees");

        SectionPlaceholderView view = _get(SectionPlaceholderView.class);
        Paragraph message = _get(view, Paragraph.class,
                spec -> spec.withClasses("clinicos-empty-state-message"));

        assertThat(message.getText()).isEqualTo("سيتم تفعيل هذا القسم قريباً.");
    }

    @Test
    void showsSubtitleForMyEvaluationSection() {
        UI.getCurrent().navigate("my-evaluation");

        SectionPlaceholderView view = _get(SectionPlaceholderView.class);
        Paragraph subtitle = _get(view, Paragraph.class,
                spec -> spec.withClasses("clinicos-section-sub"));

        assertThat(subtitle.getText()).isEqualTo("تقييم الأداء الشخصي");
    }

    @Test
    void showsEmptyStateMessageForMyEvaluationSection() {
        UI.getCurrent().navigate("my-evaluation");

        SectionPlaceholderView view = _get(SectionPlaceholderView.class);
        Paragraph message = _get(view, Paragraph.class,
                spec -> spec.withClasses("clinicos-empty-state-message"));

        assertThat(message.getText()).isEqualTo("سيتم تفعيل هذا القسم قريباً.");
    }

    @Test
    void showsSubtitleForPrepSection() {
        UI.getCurrent().navigate("prep");

        SectionPlaceholderView view = _get(SectionPlaceholderView.class);
        Paragraph subtitle = _get(view, Paragraph.class,
                spec -> spec.withClasses("clinicos-section-sub"));

        assertThat(subtitle.getText()).isEqualTo("إعداد قوائم الإجراءات الطبية");
    }

    @Test
    void showsEmptyStateMessageForPrepSection() {
        UI.getCurrent().navigate("prep");

        SectionPlaceholderView view = _get(SectionPlaceholderView.class);
        Paragraph message = _get(view, Paragraph.class,
                spec -> spec.withClasses("clinicos-empty-state-message"));

        assertThat(message.getText()).isEqualTo("سيتم تفعيل هذا القسم قريباً.");
    }
}
