package com.clinicos.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Base64;
import java.util.Map;

import com.clinicos.identity.api.SignupService;
import com.clinicos.identity.api.SignupService.SignupConflictException;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.identity.api.SignupService.SignupResult;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.data.binder.ValidationResult;
import com.vaadin.flow.data.binder.Validator;
import com.vaadin.flow.data.validator.StringLengthValidator;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Self-service clinic sign-up (UC-001, Phase 1b): an embedded split-screen page
 * (marketing hero + signup card) built on the same auth shell as {@link LoginView}.
 * The form provisions a new clinic tenant plus its first owner in one step and runs
 * before authentication, hence {@code @AnonymousAllowed}.
 *
 * <p>On success the new clinic's {@code activity_log} is written (the only
 * write path that both clinic and membership ids come from the signup
 * function's own result), then the user is sent to {@code /login?signup=success}
 * where they sign in normally.
 *
 * <p>Derived from {@code 01_login} Stitch asset via the LoginView pattern.
 * RTL layout for Arabic UI.
 */
@Route(value = "signup", autoLayout = false)
@AnonymousAllowed
public class SignupView extends Div {

    static final String BRAND_LOGO_DATA_URI = loadBrandLogoDataUri();

    private static String loadBrandLogoDataUri() {
        try (InputStream in = SignupView.class.getResourceAsStream("/branding/logo.png")) {
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SignupView.class);

    public static class SignupForm {
        private String clinicName;
        private String fullName;
        private String username;
        private String password;
        private String confirmPassword;
        private String email;

        public String getClinicName() {
            return clinicName;
        }

        public void setClinicName(String clinicName) {
            this.clinicName = clinicName;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getConfirmPassword() {
            return confirmPassword;
        }

        public void setConfirmPassword(String confirmPassword) {
            this.confirmPassword = confirmPassword;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }
    }

    private final SignupService signupService;
    private final ActivityLogService activityLogService;

    private final TextField clinicNameField = new TextField("اسم العيادة");
    private final TextField fullNameField = new TextField("الاسم الكامل");
    private final TextField usernameField = new TextField("اسم المستخدم");
    private final PasswordField passwordField = new PasswordField("كلمة المرور");
    private final PasswordField confirmPasswordField = new PasswordField("تأكيد كلمة المرور");
    private final TextField emailField = new TextField("البريد الإلكتروني (اختياري)");
    private final Button submitButton = new Button("إنشاء العيادة");
    private final Binder<SignupForm> binder = new Binder<>(SignupForm.class);
    private final Div card;

    public SignupView(SignupService signupService, ActivityLogService activityLogService) {
        this.signupService = signupService;
        this.activityLogService = activityLogService;

        addClassNames("min-h-screen", "w-full", "flex", "flex-col", "lg:flex-row");
        addClassName("relative");
        getElement().setAttribute("dir", "rtl");
        getStyle().set("background-color", "#f6faf8");
        getStyle().set("background-image",
                "radial-gradient(at 85% 15%, rgba(16, 185, 129, 0.08) 0px, transparent 50%), " +
                "radial-gradient(at 10% 90%, rgba(10, 46, 41, 0.05) 0px, transparent 50%), " +
                "linear-gradient(rgba(10, 46, 41, 0.03) 1px, transparent 1px), " +
                "linear-gradient(90deg, rgba(10, 46, 41, 0.03) 1px, transparent 1px)");
        getStyle().set("background-size", "100% 100%, 100% 100%, 32px 32px, 32px 32px");

        card = buildCard();

        add(buildHero(), buildPanel());

        binder.setBean(new SignupForm());
        bindFields();
        submitButton.addClickListener(event -> onSubmit());
    }

    private Div buildHero() {
        Div hero = new Div();
        hero.addClassNames("lg:w-7/12", "w-full", "subtle-mesh", "relative", "flex", "flex-col",
                "justify-center", "p-8", "sm:p-14", "lg:p-20");

        Div accentBar = new Div();
        accentBar.addClassNames("w-12", "h-1.5", "bg-gradient-to-r", "from-emerald-500",
                "to-teal-900", "rounded-full", "mb-6");

        H1 title = new H1("أنشئ عيادتك");
        title.addClassNames("text-3xl", "sm:text-4xl", "lg:text-5xl", "font-extrabold",
                "text-teal-900", "leading-[1.25]", "tracking-tight", "m-0");

        Paragraph sub = new Paragraph(
                "سجّل عيادتك الآن وابدأ إدارة فريقك وعملياتك اليومية في مكان واحد متكامل، " +
                "بأدوات تتبع وتقارير تساعد على رفع الكفاءة والإنتاجية.");
        sub.addClassNames("mt-6", "text-base", "sm:text-lg", "text-slate-600", "leading-relaxed",
                "font-normal", "m-0");

        Div centerContent = new Div();
        centerContent.addClassNames("my-auto", "py-12", "max-w-2xl");
        centerContent.add(accentBar, title, sub);

        hero.add(centerContent);
        return hero;
    }

    private Div buildPanel() {
        Div panel = new Div();
        panel.addClassNames("lg:w-5/12", "w-full", "relative", "flex", "flex-col", "items-center",
                "justify-center", "p-6", "sm:p-12", "lg:p-16", "shadow-2xl", "z-10",
                "border-e", "border-emerald-500/20");
        panel.getStyle().set("background", "radial-gradient(circle at 10% 20%, #0d3832 0%, #0a2e29 60%, #061e1b 100%)");

        Div orb1 = new Div();
        orb1.addClassNames("absolute", "top-1/4", "start-[-5rem]", "w-72", "h-72",
                "bg-emerald-500/15", "rounded-full", "blur-3xl", "pointer-events-none");
        Div orb2 = new Div();
        orb2.addClassNames("absolute", "bottom-10", "end-0", "w-64", "h-64",
                "bg-teal-900/50", "rounded-full", "blur-2xl", "pointer-events-none");

        panel.add(orb1, orb2, card);
        return panel;
    }

    private Div buildCard() {
        Div c = new Div();
        c.addClassNames("w-full", "max-w-[480px]", "bg-white", "rounded-3xl", "p-8", "sm:p-10",
                "shadow-2xl", "relative", "z-10", "border", "border-emerald-50");

        Div logoContainer = new Div();
        logoContainer.addClassNames("flex", "flex-col", "items-center", "text-center", "mb-8");
        Div logoBox = new Div();
        logoBox.addClassNames("w-24", "h-24", "mb-6", "flex", "items-center", "justify-center",
                "bg-white", "rounded-2xl", "shadow-lg", "border", "border-slate-200/80", "p-1.5");
        Image logo = new Image(BRAND_LOGO_DATA_URI, "ClinicOS");
        logo.addClassNames("object-contain", "drop-shadow", "w-full", "h-full");
        logoBox.add(logo);

        H2 heading = new H2("إنشاء عيادة جديدة");
        heading.addClassNames("text-2xl", "font-bold", "tracking-tight", "text-teal-900", "m-0");
        Paragraph subheading = new Paragraph("ابدأ رحلتك نحو إدارة أفضل");
        subheading.addClassNames("text-sm", "font-medium", "text-slate-500", "mt-1", "m-0");

        logoContainer.add(logoBox, heading, subheading);

        clinicNameField.setWidthFull();
        clinicNameField.addClassNames("clinicos-signup-field");
        fullNameField.setWidthFull();
        fullNameField.addClassNames("clinicos-signup-field");
        usernameField.setWidthFull();
        usernameField.addClassNames("clinicos-signup-field");
        passwordField.setWidthFull();
        passwordField.addClassNames("clinicos-signup-field");
        confirmPasswordField.setWidthFull();
        confirmPasswordField.addClassNames("clinicos-signup-field");
        emailField.setWidthFull();
        emailField.addClassNames("clinicos-signup-field");

        submitButton.setWidthFull();
        submitButton.addClassNames("mt-6", "bg-teal-900", "text-white", "font-semibold", "rounded-xl",
                "py-3.5", "shadow-lg", "hover:bg-teal-800", "transition-colors");
        submitButton.setDisableOnClick(true);

        Div formFields = new Div();
        formFields.addClassNames("flex", "flex-col", "gap-4");
        formFields.add(clinicNameField, fullNameField, usernameField, passwordField,
                confirmPasswordField, emailField, submitButton);

        c.add(logoContainer, formFields);
        return c;
    }

    private void bindFields() {
        binder.forField(clinicNameField)
                .asRequired("اسم العيادة مطلوب")
                .bind(SignupForm::getClinicName, SignupForm::setClinicName);
        binder.forField(fullNameField)
                .asRequired("الاسم الكامل مطلوب")
                .bind(SignupForm::getFullName, SignupForm::setFullName);
        binder.forField(usernameField)
                .asRequired("اسم المستخدم مطلوب")
                .bind(SignupForm::getUsername, SignupForm::setUsername);
        binder.forField(passwordField)
                .asRequired("كلمة المرور مطلوبة")
                .withValidator(new StringLengthValidator("كلمة المرور يجب أن تكون 8 محارف على الأقل", 8, null))
                .bind(SignupForm::getPassword, SignupForm::setPassword);
        binder.forField(confirmPasswordField)
                .asRequired("تأكيد كلمة المرور مطلوب")
                .withValidator(confirmMatches(passwordField))
                .bind(SignupForm::getConfirmPassword, SignupForm::setConfirmPassword);
        binder.forField(emailField)
                .withValidator(Validator.from(email -> email == null || email.isBlank() || email.contains("@"),
                        "صيغة البريد الإلكتروني غير صحيحة"))
                .bind(SignupForm::getEmail, SignupForm::setEmail);
    }

    private Validator<String> confirmMatches(PasswordField passwordField) {
        return (value, context) -> {
            if (value == null || passwordField.getValue().equals(value)) {
                return ValidationResult.ok();
            }
            return ValidationResult.error("كلمتا المرور غير متطابقتين");
        };
    }

    private void onSubmit() {
        if (!binder.validate().isOk()) {
            submitButton.setEnabled(true);
            return;
        }
        SignupForm form = binder.getBean();
        SignupRequest request = new SignupRequest(
                form.getClinicName().trim(),
                form.getFullName().trim(),
                form.getUsername().trim(),
                blankToNull(form.getEmail()),
                form.getPassword());

        SignupResult result;
        try {
            result = signupService.signUp(request);
        } catch (SignupConflictException conflict) {
            renderConflict(conflict);
            submitButton.setEnabled(true);
            return;
        } catch (Exception e) {
            log.error("Signup failed for clinic='{}' username='{}'", request.clinicName(), request.username(), e);
            submitButton.setEnabled(true);
            new Notification("حدث خطأ أثناء إنشاء العيادة، حاول مرة أخرى", 4000, Notification.Position.BOTTOM_CENTER)
                    .open();
            return;
        }
        afterSignup(result);
    }

    private void afterSignup(SignupResult result) {
        TenantContext.set(result.clinicId());
        try {
            activityLogService.log(result.clinicId(), result.membershipId(), "signup", "clinic");
        } finally {
            TenantContext.clear();
        }
        UI.getCurrent().navigate(
                "login",
                QueryParameters.simple(Map.of("signup", "success", "clinic", result.clinicSlug())));
    }

    private void renderConflict(SignupConflictException conflict) {
        switch (conflict.getField()) {
            case USERNAME -> showFieldError(usernameField, "اسم المستخدم مستخدم بالفعل");
            case EMAIL -> showFieldError(emailField, "البريد الإلكتروني مستخدم بالفعل");
            case CLINIC_SLUG -> showFieldError(clinicNameField, "اسم العيادة مستخدم بالفعل");
        }
    }

    private static void showFieldError(TextField field, String message) {
        field.setInvalid(true);
        field.setErrorMessage(message);
        field.focus();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}