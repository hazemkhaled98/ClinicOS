package com.clinicos.ui;

import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.H1;
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
    void showsHeadingWhenClinicIsBound() {
        VaadinSession.getCurrent().setAttribute(ClinicPickerView.SESSION_CLINIC_ID, UUID.randomUUID());

        HelloView view = new HelloView();

        H1 heading = _get(view, H1.class);
        assertThat(heading.getText()).isEqualTo("ClinicOS");
    }

    @Test
    void rootRouteRendersInsideTheAppShell() {
        UI.getCurrent().navigate(HelloView.class);

        assertThat(_get(MainLayout.class)).isNotNull();
    }
}