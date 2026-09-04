package com.clinicos.ui;

import static com.github.mvysny.kaributesting.v10.LocatorJ._get;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.vaadin.flow.component.login.LoginForm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoginViewTest {

    @BeforeEach
    void setupVaadin() {
        MockVaadin.setup();
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
    void loginViewHasRtlAttribute() {
        LoginView view = new LoginView();
        String dir = view.getElement().getAttribute("dir");
        assertThat(dir).isEqualTo("rtl");
    }
}
