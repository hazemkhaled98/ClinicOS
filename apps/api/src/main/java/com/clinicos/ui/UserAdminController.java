package com.clinicos.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserCreateRequest;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.EmployeeService.EmployeeRequest;
import com.clinicos.staff.api.EmployeeService.EmployeeValidationException;
import com.clinicos.staff.api.EmployeeService.StaffRole;
import com.clinicos.identity.api.UserAdminService.UserValidationException;

import jakarta.servlet.http.HttpSession;

@Controller
public class UserAdminController {

    private final LayoutModel layoutModel;
    private final UserAdminService userAdminService;
    private final EmployeeService employeeService;
    private final ActivityLogService activityLogService;
    private final TransactionTemplate transactionTemplate;
    private final Argon2PasswordEncoder passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public UserAdminController(LayoutModel layoutModel, UserAdminService userAdminService,
            EmployeeService employeeService, ActivityLogService activityLogService, TransactionTemplate transactionTemplate) {
        this.layoutModel = layoutModel;
        this.userAdminService = userAdminService;
        this.employeeService = employeeService;
        this.activityLogService = activityLogService;
        this.transactionTemplate = transactionTemplate;
    }

    @GetMapping("/admin-dashboard/users")
    public String users(HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        renderCard(model, clinicId, AdminAccess.membershipId(session), Map.of(), null, UserForm.empty());
        return "admin/users-page";
    }

    @PostMapping("/admin-dashboard/users")
    public String createUser(UserForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        validate(form, fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                String email = form.email() == null || form.email().isBlank() ? null : form.email().trim();
                transactionTemplate.executeWithoutResult(status -> {
                    UserSummary created = userAdminService.create(clinicId, new UserCreateRequest(
                            form.username().trim(), form.fullName().trim(), email, passwordEncoder.encode(form.password())));
                    Employee employee = employeeService.create(clinicId, new EmployeeRequest(
                            form.fullName().trim(), StaffRole.ASSISTANT, null, null, null, null, false, null));
                    userAdminService.linkEmployee(clinicId, created.membershipId(), employee.id());
                });
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.create", "user");
            } catch (UserValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            } catch (EmployeeValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            } catch (IllegalArgumentException e) {
                fieldErrors.put("username", e.getMessage());
            }
        }
        renderCard(model, clinicId, AdminAccess.membershipId(session), fieldErrors,
                fieldErrors.isEmpty() ? null : "add", form);
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تم إضافة المستخدم");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/users :: usersCard";
    }

    @PostMapping("/admin-dashboard/users/{userId}/password")
    public String changePassword(@PathVariable UUID userId, @RequestParam String newPassword,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        if (newPassword == null || newPassword.isBlank()) {
            fieldErrors.put("password", "كلمة المرور مطلوبة");
        }
        if (fieldErrors.isEmpty()) {
            try {
                userAdminService.changePassword(clinicId, userId, passwordEncoder.encode(newPassword));
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.password_change", "user");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("user", e.getMessage());
            }
        }
        renderCard(model, clinicId, AdminAccess.membershipId(session), fieldErrors,
                fieldErrors.isEmpty() ? null : "password", UserForm.empty());
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تم تغيير كلمة المرور");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/users :: usersCard";
    }

    @PostMapping("/admin-dashboard/users/{userId}/suspend")
    public String suspend(@PathVariable UUID userId, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            userAdminService.suspend(clinicId, userId, AdminAccess.membershipId(session));
            activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.suspend", "user");
        } catch (IllegalArgumentException e) {
            fieldErrors.put("user", e.getMessage());
        }
        renderCard(model, clinicId, AdminAccess.membershipId(session), fieldErrors,
                fieldErrors.isEmpty() ? null : "suspend", UserForm.empty());
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تم تعليق المستخدم");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/users :: usersCard";
    }

    @PostMapping("/admin-dashboard/users/{userId}/reactivate")
    public String reactivate(@PathVariable UUID userId, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            userAdminService.reactivate(clinicId, userId);
            activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.reactivate", "user");
        } catch (IllegalArgumentException e) {
            fieldErrors.put("user", e.getMessage());
        }
        renderCard(model, clinicId, AdminAccess.membershipId(session), fieldErrors,
                fieldErrors.isEmpty() ? null : "reactivate", UserForm.empty());
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تم تفعيل المستخدم");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/users :: usersCard";
    }

    @PostMapping("/admin-dashboard/users/{userId}/role")
    public String assignRole(@PathVariable UUID userId, @RequestParam String roleCode,
            @RequestParam UUID membershipId, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            userAdminService.assignRole(clinicId, membershipId, roleCode);
            activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.assign_role", "user");
        } catch (IllegalArgumentException e) {
            fieldErrors.put("user", e.getMessage());
        }
        renderCard(model, clinicId, AdminAccess.membershipId(session), fieldErrors,
                fieldErrors.isEmpty() ? null : "role", UserForm.empty());
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تم تغيير الدور");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/users :: usersCard";
    }

    private void renderCard(Model model, UUID clinicId, UUID currentMembershipId, Map<String, String> fieldErrors,
            String errorScope, UserForm addForm) {
        model.addAttribute("users", userAdminService.list(clinicId));
        model.addAttribute("currentMembershipId", currentMembershipId);
        model.addAttribute("userErrors", fieldErrors == null ? Map.of() : fieldErrors);
        model.addAttribute("userErrorScope", errorScope);
        model.addAttribute("addForm", addForm);
        model.addAttribute("roleNames", roleNames());
    }

    private static Map<String, String> roleNames() {
        return Map.of(
                "owner", LayoutModel.roleDisplayName("owner"),
                "manager", LayoutModel.roleDisplayName("manager"),
                "assistant", LayoutModel.roleDisplayName("assistant"),
                "receptionist", LayoutModel.roleDisplayName("receptionist"));
    }

    private static void validate(UserForm form, Map<String, String> errors) {
        if (form.username() == null || form.username().isBlank()) {
            errors.put("username", "اسم المستخدم مطلوب");
        }
        if (form.fullName() == null || form.fullName().isBlank()) {
            errors.put("fullName", "الاسم الكامل مطلوب");
        }
        if (form.password() == null || form.password().isBlank()) {
            errors.put("password", "كلمة المرور مطلوبة");
        }
    }

    public record UserForm(String username, String fullName, String email, String password) {

        static UserForm empty() {
            return of("", "", "", "");
        }

        static UserForm of(String username, String fullName, String email, String password) {
            return new UserForm(username, fullName, email, password);
        }
    }
}