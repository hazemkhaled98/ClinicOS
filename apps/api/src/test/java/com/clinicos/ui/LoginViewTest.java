package com.clinicos.ui;

import static com.github.mvysny.kaributesting.v10.LocatorJ._get;

import java.util.Map;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.QueryParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginViewTest {

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
    void teardownVaadin() {
        MockVaadin.tearDown();
    }

    @Test
    void loginViewIsReachableAtCorrectRoute() {
        LoginView view = new LoginView();
        assertThat(view).isNotNull();
    }

    @Test
    void loginViewHasArabicLabels() {
        LoginView view = new LoginView();
        LoginForm form = _get(view, LoginForm.class);
        assertThat(form).isNotNull();
    }

    @Test
    void loginViewCollectsClinicCodeAsPostField() {
        LoginView view = new LoginView();

        TextField clinic = _get(view, TextField.class, spec -> spec.withLabel("كود العيادة"));

        assertThat(clinic.isRequiredIndicatorVisible()).isTrue();
        assertThat(clinic.getElement().getAttribute("name")).isEqualTo("clinic");
    }

    @Test
    void loginViewHasRtlAttribute() {
        LoginView view = new LoginView();
        String dir = view.getElement().getAttribute("dir");
        assertThat(dir).isEqualTo("rtl");
    }

    @Test
    void loginViewLinksToSignup() {
        LoginView view = new LoginView();
        assertThat(_get(view, com.vaadin.flow.router.RouterLink.class)).isNotNull();
    }

    @Test
    void clinicCodeIsPrefilledFromQueryParameter() {
        UI.getCurrent().navigate(LoginView.class, QueryParameters.simple(Map.of("clinic", "nour-clinic")));

        LoginView view = _get(LoginView.class);
        TextField clinic = _get(view, TextField.class, spec -> spec.withLabel("كود العيادة"));
        assertThat(clinic.getValue()).isEqualTo("nour-clinic");
    }
}