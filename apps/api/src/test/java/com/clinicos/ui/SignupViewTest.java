package com.clinicos.ui;

import static com.github.mvysny.kaributesting.v10.LocatorJ._click;
import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.clinicos.identity.api.SignupService;
import com.clinicos.identity.api.SignupService.SignupConflictException;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.identity.api.SignupService.SignupResult;
import com.clinicos.shared.ActivityLogService;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;

@ExtendWith(MockitoExtension.class)
class SignupViewTest {

    private static Routes routes;

    @Mock
    private SignupService signupService;

    @Mock
    private ActivityLogService activityLogService;

    @BeforeAll
    static void discoverRoutes() {
        routes = new Routes().autoDiscoverViews("com.clinicos");
    }

    @BeforeEach
    void setup() {
        MockVaadin.setup(routes);
    }

    @AfterEach
    void teardown() {
        MockVaadin.tearDown();
    }

    @Test
    void requiredFieldsAreValidated() {
        SignupView view = new SignupView(signupService, activityLogService);

        TextField clinicName = clinicNameField(view);
        TextField fullName = fullNameField(view);
        TextField username = usernameField(view);
        TextField email = emailField(view);
        PasswordField password = passwordField(view);
        PasswordField confirmPassword = confirmPasswordField(view);

        password.setValue("secret-password");
        confirmPassword.setValue("secret-password");

        _click(submitButton(view));

        assertThat(clinicName.isInvalid()).isTrue();
        assertThat(fullName.isInvalid()).isTrue();
        assertThat(username.isInvalid()).isTrue();
        assertThat(email.isInvalid()).isFalse();
    }

    @Test
    void passwordBelowMinimumLengthIsRejected() {
        SignupView view = new SignupView(signupService, activityLogService);

        fillRequiredFields(view, "العيادة", "أحمد", "user", "short", "short");

        _click(submitButton(view));

        assertThat(passwordField(view).isInvalid()).isTrue();
        assertThat(passwordField(view).getErrorMessage()).contains("8");
    }

    @Test
    void passwordMismatchIsRejected() {
        SignupView view = new SignupView(signupService, activityLogService);

        fillRequiredFields(view, "العيادة", "أحمد", "user", "secret-password", "other-password");

        _click(submitButton(view));

        assertThat(confirmPasswordField(view).isInvalid()).isTrue();
        assertThat(confirmPasswordField(view).getErrorMessage()).isEqualTo("كلمتا المرور غير متطابقتين");
    }

    @Test
    void serverUsernameConflictRendersInlineOnUsernameField() {
        when(signupService.signUp(org.mockito.ArgumentMatchers.any(SignupRequest.class)))
                .thenThrow(new SignupConflictException(
                        SignupConflictException.Field.USERNAME, "duplicate key value violates unique constraint"));

        SignupView view = new SignupView(signupService, activityLogService);
        fillRequiredFields(view, "العيادة", "أحمد", "taken-user", "secret-password", "secret-password");

        _click(submitButton(view));

        TextField username = usernameField(view);
        assertThat(username.isInvalid()).isTrue();
        assertThat(username.getErrorMessage()).isEqualTo("اسم المستخدم مستخدم بالفعل");
    }

    @Test
    void serverEmailConflictRendersInlineOnEmailField() {
        when(signupService.signUp(org.mockito.ArgumentMatchers.any(SignupRequest.class)))
                .thenThrow(new SignupConflictException(
                        SignupConflictException.Field.EMAIL, "duplicate key value violates unique constraint"));

        SignupView view = new SignupView(signupService, activityLogService);
        fillRequiredFields(view, "العيادة", "أحمد", "taken-user", "secret-password", "secret-password");

        _click(submitButton(view));

        TextField email = emailField(view);
        assertThat(email.isInvalid()).isTrue();
        assertThat(email.getErrorMessage()).isEqualTo("البريد الإلكتروني مستخدم بالفعل");
    }

    @Test
    void serverSlugConflictRendersInlineOnClinicNameField() {
        when(signupService.signUp(org.mockito.ArgumentMatchers.any(SignupRequest.class)))
                .thenThrow(new SignupConflictException(
                        SignupConflictException.Field.CLINIC_SLUG, "duplicate key value violates unique constraint"));

        SignupView view = new SignupView(signupService, activityLogService);
        fillRequiredFields(view, "العيادة", "أحمد", "taken-user", "secret-password", "secret-password");

        _click(submitButton(view));

        TextField clinicName = clinicNameField(view);
        assertThat(clinicName.isInvalid()).isTrue();
        assertThat(clinicName.getErrorMessage()).isEqualTo("اسم العيادة مستخدم بالفعل");
    }

    @Test
    void successfulSignupWritesActivityLogAndNavigatesToLogin() {
        UUID clinicId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        SignupResult result = new SignupResult(UUID.randomUUID(), clinicId, membershipId, "nour-clinic");
        when(signupService.signUp(org.mockito.ArgumentMatchers.any(SignupRequest.class))).thenReturn(result);

        SignupView view = new SignupView(signupService, activityLogService);
        fillRequiredFields(view, "Nour Clinic", "Dr Ahmed", "nour-owner", "secret-password", "secret-password");

        _click(submitButton(view));

        ArgumentCaptor<SignupRequest> captor = ArgumentCaptor.forClass(SignupRequest.class);
        verify(signupService).signUp(captor.capture());
        assertThat(captor.getValue().clinicName()).isEqualTo("Nour Clinic");
        assertThat(captor.getValue().rawPassword()).isEqualTo("secret-password");

        verify(activityLogService).log(clinicId, membershipId, "signup", "clinic");

        assertThat(_get(LoginView.class)).isNotNull();
    }

    private static void fillRequiredFields(SignupView view, String clinicName, String fullName,
            String username, String password, String confirmPassword) {
        clinicNameField(view).setValue(clinicName);
        fullNameField(view).setValue(fullName);
        usernameField(view).setValue(username);
        passwordField(view).setValue(password);
        confirmPasswordField(view).setValue(confirmPassword);
    }

    private static TextField clinicNameField(SignupView view) {
        return _get(view, TextField.class, spec -> spec.withLabel("اسم العيادة"));
    }

    private static TextField fullNameField(SignupView view) {
        return _get(view, TextField.class, spec -> spec.withLabel("الاسم الكامل"));
    }

    private static TextField usernameField(SignupView view) {
        return _get(view, TextField.class, spec -> spec.withLabel("اسم المستخدم"));
    }

    private static TextField emailField(SignupView view) {
        return _get(view, TextField.class, spec -> spec.withLabel("البريد الإلكتروني (اختياري)"));
    }

    private static PasswordField passwordField(SignupView view) {
        return _get(view, PasswordField.class, spec -> spec.withLabel("كلمة المرور"));
    }

    private static PasswordField confirmPasswordField(SignupView view) {
        return _get(view, PasswordField.class, spec -> spec.withLabel("تأكيد كلمة المرور"));
    }

    private static Button submitButton(SignupView view) {
        return _get(view, Button.class, spec -> spec.withText("إنشاء العيادة"));
    }
}