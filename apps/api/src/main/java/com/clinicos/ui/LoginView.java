package com.clinicos.ui;

import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Login screen for ClinicOS, using Vaadin's built-in {@link LoginForm}
 * component. The form is automatically wired to Spring Security's form-login
 * processing. RTL layout for Arabic UI.
 */
@Route("login")
@AnonymousAllowed
public class LoginView extends VerticalLayout {

    public LoginView() {
        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setAlignItems(Alignment.CENTER);

        LoginForm form = new LoginForm();
        form.setI18n(createArabicLabels());
        form.setForgotPasswordButtonVisible(false);
        add(form);

        getElement().setAttribute("dir", "rtl");
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
