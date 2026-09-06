package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.identity.api.SignupService;
import com.clinicos.identity.api.SignupService.SignupConflictException;
import com.clinicos.identity.api.SignupService.SignupConflictException.Field;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.identity.api.SignupService.SignupResult;
import com.clinicos.shared.ActivityLogService;

class AuthControllerTest {

    private SignupService signupService;
    private ActivityLogService activityLogService;
    private AuthController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        signupService = mock(SignupService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new AuthController(signupService, activityLogService);
        model = new ExtendedModelMap();
    }

    @Test
    void loginPageMarksErrorFlagAndPrefillsClinic() {
        controller.login("true", "myclinic", null, model);

        assertThat(model.getAttribute("error")).isEqualTo(true);
        assertThat(model.getAttribute("clinic")).isEqualTo("myclinic");
        assertThat(model.getAttribute("signupSuccess")).isEqualTo(false);
    }

    @Test
    void shortPasswordIsRejectedWithArabicMessage() {
        AuthController.SignupForm form = form();
        form.setPassword("short");
        form.setConfirmPassword("short");

        String view = controller.signup(form, model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString()
                .contains("كلمة المرور يجب أن تكون 8 محارف على الأقل");
    }

    @Test
    void mismatchedPasswordsAreRejected() {
        AuthController.SignupForm form = form();
        form.setPassword("correct-password");
        form.setConfirmPassword("other-password");

        String view = controller.signup(form, model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString()
                .contains("كلمتا المرور غير متطابقتين");
    }

    @Test
    void duplicateUsernameMapsToUsernameFieldError() {
        when(signupService.signUp(any(SignupRequest.class)))
                .thenThrow(new SignupConflictException(Field.USERNAME, "dup"));

        String view = controller.signup(form(), model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString()
                .contains("اسم المستخدم مستخدم بالفعل");
    }

    @Test
    void duplicateEmailMapsToEmailFieldError() {
        when(signupService.signUp(any(SignupRequest.class)))
                .thenThrow(new SignupConflictException(Field.EMAIL, "dup"));

        String view = controller.signup(form(), model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString()
                .contains("البريد الإلكتروني مستخدم بالفعل");
    }

    @Test
    void duplicateClinicSlugMapsToClinicNameFieldError() {
        when(signupService.signUp(any(SignupRequest.class)))
                .thenThrow(new SignupConflictException(Field.CLINIC_SLUG, "dup"));

        String view = controller.signup(form(), model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString()
                .contains("اسم العيادة مستخدم بالفعل");
    }

    @Test
    void blankClinicNameIsRejected() {
        AuthController.SignupForm form = form();
        form.setClinicName(null);

        String view = controller.signup(form, model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString().contains("اسم العيادة مطلوب");
    }

    @Test
    void blankFullNameIsRejected() {
        AuthController.SignupForm form = form();
        form.setFullName("   ");

        String view = controller.signup(form, model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString().contains("الاسم الكامل مطلوب");
    }

    @Test
    void blankUsernameIsRejected() {
        AuthController.SignupForm form = form();
        form.setUsername(null);

        String view = controller.signup(form, model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString().contains("اسم المستخدم مطلوب");
    }

    @Test
    void blankPasswordWithFilledConfirmIsSafeAndReportsPasswordError() {
        AuthController.SignupForm form = form();
        form.setPassword(null);
        form.setConfirmPassword("correct-password");

        String view = controller.signup(form, model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("fieldErrors")).asString().contains("كلمة المرور مطلوبة");
    }

    @Test
    void successfulSignupLogsActivityAndRedirectsWithClinicSlug() {
        when(signupService.signUp(any(SignupRequest.class))).thenReturn(
                new SignupResult(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "myclinic"));

        String view = controller.signup(form(), model);

        assertThat(view).isEqualTo("redirect:/login?signup=success&clinic=myclinic");
        verify(activityLogService).log(any(UUID.class), any(UUID.class),
                eq("signup"), eq("clinic"));
    }

    @Test
    void serverFailureShowsGeneralError() {
        when(signupService.signUp(any(SignupRequest.class)))
                .thenThrow(new IllegalStateException("db down"));

        String view = controller.signup(form(), model);

        assertThat(view).isEqualTo("auth/signup");
        assertThat(model.getAttribute("generalError")).asString()
                .contains("حدث خطأ أثناء إنشاء العيادة");
    }

    private static AuthController.SignupForm form() {
        AuthController.SignupForm form = new AuthController.SignupForm();
        form.setClinicName("عيادة جديدة");
        form.setFullName("المالك");
        form.setUsername("owner1");
        form.setPassword("correct-password");
        form.setConfirmPassword("correct-password");
        return form;
    }
}