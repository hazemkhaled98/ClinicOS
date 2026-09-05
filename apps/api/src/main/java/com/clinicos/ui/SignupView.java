package com.clinicos.ui;

import com.clinicos.identity.api.SignupService;
import com.clinicos.identity.api.SignupService.SignupConflictException;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.identity.api.SignupService.SignupResult;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment;
import com.vaadin.flow.component.orderedlayout.FlexComponent.JustifyContentMode;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.data.binder.ValidationResult;
import com.vaadin.flow.data.binder.Validator;
import com.vaadin.flow.data.validator.StringLengthValidator;
import com.vaadin.flow.router.QueryParameters;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

/**
 * Self-service clinic sign-up (UC-001, Phase 1b): a public form that provisions
 * a new clinic tenant plus its first owner in one step. The form runs before
 * authentication, hence {@code @AnonymousAllowed}.
 *
 * <p>On success the new clinic's {@code activity_log} is written (the only
 * write path that both clinic and membership ids come from the signup
 * function's own result), then the user is sent to {@code /login?signup=success}
 * where they sign in normally. The originally-planned immediate
 * {@code AuthenticationContext.login(...)} does not exist in this Vaadin
 * version — this redirect is the specified fallback.
 */
@Route(value = "signup", autoLayout = false)
@AnonymousAllowed
public class SignupView extends VerticalLayout {

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

    public SignupView(SignupService signupService, ActivityLogService activityLogService) {
        this.signupService = signupService;
        this.activityLogService = activityLogService;

        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);
        setAlignItems(Alignment.CENTER);
        getElement().setAttribute("dir", "rtl");

        H2 heading = new H2("إنشاء عيادة جديدة");
        heading.getElement().setAttribute("dir", "rtl");

        VerticalLayout card = new VerticalLayout(heading, clinicNameField, fullNameField,
                usernameField, passwordField, confirmPasswordField, emailField, submitButton);
        card.setWidth("360px");
        card.setPadding(true);
        card.setSpacing(true);
        clinicNameField.setWidthFull();
        fullNameField.setWidthFull();
        usernameField.setWidthFull();
        passwordField.setWidthFull();
        confirmPasswordField.setWidthFull();
        emailField.setWidthFull();
        submitButton.setWidthFull();
        submitButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        submitButton.setDisableOnClick(true);

        binder.setBean(new SignupForm());
        bindFields();
        submitButton.addClickListener(event -> onSubmit());

        add(card);
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
        try {
            if (!binder.validate().isOk()) {
                submitButton.setEnabled(true);
                return;
            }
            SignupForm form = binder.getBean();
            SignupRequest request = new SignupRequest(
                    form.getClinicName(),
                    form.getFullName(),
                    form.getUsername(),
                    blankToNull(form.getEmail()),
                    form.getPassword());
            SignupResult result = signupService.signUp(request);
            afterSignup(result);
        } catch (SignupConflictException conflict) {
            renderConflict(conflict);
            submitButton.setEnabled(true);
        } catch (Exception e) {
            submitButton.setEnabled(true);
            new Notification("حدث خطأ أثناء إنشاء العيادة، حاول مرة أخرى", 4000, Notification.Position.BOTTOM_CENTER)
                    .open();
        }
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
                QueryParameters.of("signup", "success"));
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