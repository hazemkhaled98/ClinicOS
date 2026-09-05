package com.clinicos.ui;

import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.login.LoginOverlay;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Login screen for ClinicOS, using Vaadin's built-in {@link LoginOverlay}
 * component. The form posts to {@code /login} ({@link LoginOverlay#setAction}),
 * which Spring Security's form-login processes. A clinic-code field sits in the
 * overlay's custom form area; its {@code name="clinic"} attribute is what makes
 * it submit with the POST, and it feeds the clinic-scoped authenticator. RTL
 * layout for Arabic UI.
 */
@Route(value = "login", autoLayout = false)
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final LoginOverlay overlay;
    private final TextField clinicField;

    public LoginView() {
        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setAlignItems(Alignment.CENTER);

        overlay = new LoginOverlay();
        overlay.setAction("login");
        overlay.setI18n(createArabicLabels());
        overlay.setForgotPasswordButtonVisible(false);

        clinicField = new TextField("كود العيادة");
        clinicField.getElement().setAttribute("name", "clinic");
        clinicField.setRequiredIndicatorVisible(true);
        overlay.getCustomFormArea().add(clinicField);

        Paragraph signupHint = new Paragraph(new RouterLink("ليس لديك حساب؟ أنشئ عيادة جديدة", SignupView.class));
        signupHint.getStyle().set("margin-top", "1rem");
        signupHint.getStyle().set("color", "var(--ink)");
        overlay.getFooter().add(signupHint);

        add(overlay);
        overlay.setOpened(true);

        getElement().setAttribute("dir", "rtl");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        var params = event.getLocation().getQueryParameters();
        if (params.getParameters().containsKey("error")) {
            overlay.setError(true);
        }
        params.getSingleParameter("clinic").ifPresent(clinicField::setValue);
        if (params.getSingleParameter("signup").map("success"::equals).orElse(false)) {
            Paragraph banner = new Paragraph("تم إنشاء العيادة بنجاح، سجّل الدخول لبدء العمل");
            banner.getStyle().set("margin-bottom", "1rem");
            banner.getStyle().set("color", "var(--teal)");
            banner.getStyle().set("font-weight", "600");
            addComponentAtIndex(0, banner);
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