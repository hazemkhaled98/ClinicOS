package com.clinicos.ui;

import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Login screen for ClinicOS, using Vaadin's built-in {@link LoginForm}
 * component. The form posts to {@code /login} ({@link LoginForm#setAction}),
 * which Spring Security's form-login processes. RTL layout for Arabic UI.
 */
@Route(value = "login", autoLayout = false)
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final LoginForm form;

    public LoginView() {
        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setAlignItems(Alignment.CENTER);

        form = new LoginForm();
        form.setAction("login");
        form.setI18n(createArabicLabels());
        form.setForgotPasswordButtonVisible(false);
        add(form);

        getElement().setAttribute("dir", "rtl");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        if (event.getLocation().getQueryParameters().getParameters().containsKey("error")) {
            form.setError(true);
        }
    }

    private LoginI18n createArabicLabels() {
        LoginI18n i18n = new LoginI18n();

        LoginI18n.Form form = new LoginI18n.Form();
        form.setUsername("اسم المستخدم");
        form.setPassword("كلمة المرور");
        form.setSubmit("دخول");
        form.setTitle("ClinicOS");
        i18n.setForm(form);

        LoginI18n.ErrorMessage errorMessage = new LoginI18n.ErrorMessage();
        errorMessage.setTitle("❌");
        errorMessage.setMessage("اسم المستخدم أو كلمة المرور غير صحيحة");
        i18n.setErrorMessage(errorMessage);

        return i18n;
    }
}
