package com.clinicos.ui;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.identity.api.SignupService;
import com.clinicos.identity.api.SignupService.SignupConflictException;
import com.clinicos.identity.api.SignupService.SignupRequest;
import com.clinicos.identity.api.SignupService.SignupResult;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.TenantContext;

/**
 * Login and self-service sign-up (UC-001) server-rendered with Thymeleaf.
 * Replaces the Vaadin {@code LoginView}/{@code SignupView}; the controller
 * carries the same validation and conflict messages so the forms behave
 * identically.
 */
@Controller
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String SUCCESS_BANNER = "تم إنشاء العيادة بنجاح، سجّل الدخول لبدء العمل";

    private final SignupService signupService;
    private final ActivityLogService activityLogService;

    public AuthController(SignupService signupService, ActivityLogService activityLogService) {
        this.signupService = signupService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/login")
    public String login(@RequestParam(value = "error", required = false) String error,
            @RequestParam(value = "clinic", required = false) String clinic,
            @RequestParam(value = "signup", required = false) String signup,
            Model model) {
        model.addAttribute("error", error != null);
        model.addAttribute("clinic", clinic == null ? "" : clinic);
        model.addAttribute("signupSuccess", "success".equals(signup));
        model.addAttribute("successBanner", SUCCESS_BANNER);
        return "auth/login";
    }

    @GetMapping("/signup")
    public String signupForm(Model model) {
        model.addAttribute("form", new SignupForm());
        model.addAttribute("fieldErrors", Map.of());
        model.addAttribute("generalError", null);
        return "auth/signup";
    }

    @PostMapping("/signup")
    public String signup(SignupForm form, Model model) {
        Map<String, String> fieldErrors = validate(form);
        if (!fieldErrors.isEmpty()) {
            model.addAttribute("form", form);
            model.addAttribute("fieldErrors", fieldErrors);
            return "auth/signup";
        }

        SignupRequest request = new SignupRequest(
                trim(form.getClinicName()),
                trim(form.getFullName()),
                trim(form.getUsername()),
                blankToNull(form.getEmail()),
                form.getPassword());

        SignupResult result;
        try {
            result = signupService.signUp(request);
        } catch (SignupConflictException conflict) {
            model.addAttribute("form", form);
            model.addAttribute("fieldErrors", Map.of(conflictFieldName(conflict), conflictMessage(conflict)));
            return "auth/signup";
        } catch (Exception e) {
            log.error("Signup failed for clinic='{}' username='{}'", request.clinicName(), request.username(), e);
            model.addAttribute("form", form);
            model.addAttribute("fieldErrors", Map.of());
            model.addAttribute("generalError", "حدث خطأ أثناء إنشاء العيادة، حاول مرة أخرى");
            return "auth/signup";
        }

        TenantContext.set(result.clinicId());
        try {
            activityLogService.log(result.clinicId(), result.membershipId(), "signup", "clinic");
        } finally {
            TenantContext.clear();
        }

        return "redirect:/login?signup=success&clinic=" + result.clinicSlug();
    }

    private static Map<String, String> validate(SignupForm form) {
        Map<String, String> errors = new HashMap<>();
        require(errors, "clinicName", form.getClinicName(), "اسم العيادة مطلوب");
        require(errors, "fullName", form.getFullName(), "الاسم الكامل مطلوب");
        require(errors, "username", form.getUsername(), "اسم المستخدم مطلوب");
        if (form.getPassword() == null || form.getPassword().isBlank()) {
            errors.put("password", "كلمة المرور مطلوبة");
        } else if (form.getPassword().length() < 8) {
            errors.put("password", "كلمة المرور يجب أن تكون 8 محارف على الأقل");
        }
        if (form.getConfirmPassword() == null || form.getConfirmPassword().isBlank()) {
            errors.put("confirmPassword", "تأكيد كلمة المرور مطلوب");
        } else if (!form.getConfirmPassword().equals(form.getPassword())) {
            errors.put("confirmPassword", "كلمتا المرور غير متطابقتين");
        }
        String email = form.getEmail();
        if (email != null && !email.isBlank() && !email.contains("@")) {
            errors.put("email", "صيغة البريد الإلكتروني غير صحيحة");
        }
        return errors;
    }

    private static void require(Map<String, String> errors, String field, String value, String message) {
        if (value == null || value.isBlank()) {
            errors.put(field, message);
        }
    }

    private static String conflictFieldName(SignupConflictException conflict) {
        return switch (conflict.getField()) {
            case USERNAME -> "username";
            case EMAIL -> "email";
            case CLINIC_SLUG -> "clinicName";
        };
    }

    private static String conflictMessage(SignupConflictException conflict) {
        return switch (conflict.getField()) {
            case USERNAME -> "اسم المستخدم مستخدم بالفعل";
            case EMAIL -> "البريد الإلكتروني مستخدم بالفعل";
            case CLINIC_SLUG -> "اسم العيادة مستخدم بالفعل";
        };
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

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
}
