package com.clinicos.ui;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserCreateRequest;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.EmployeeService.EmployeeRequest;
import com.clinicos.staff.api.EmployeeService.EmployeeValidationException;
import com.clinicos.identity.api.UserAdminService.UserValidationException;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

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
        renderCard(model, clinicId, AdminAccess.membershipId(session), UserForm.empty());
        return "admin/users-page";
    }

    @PostMapping("/admin-dashboard/users")
    public String createUser(@Valid UserForm form, BindingResult binding, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.putAll(FormErrors.of(binding));
        if (fieldErrors.isEmpty()) {
            try {
                String email = blankToNull(form.getEmail());
                transactionTemplate.executeWithoutResult(status -> {
                    UserSummary created = userAdminService.create(clinicId, new UserCreateRequest(
                            form.getUsername().trim(), form.getFullName().trim(), email, passwordEncoder.encode(form.getPassword())));
                    Employee employee = employeeService.create(clinicId, new EmployeeRequest(
                            form.getFullName().trim(), null, null, null, null, false, null));
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
        renderCard(model, clinicId, AdminAccess.membershipId(session), form);
        Toasts.fromErrors(model, fieldErrors, "تم إضافة المستخدم");
        return "admin/users :: usersCard";
    }

    @PostMapping("/admin-dashboard/users/{userId}/password")
    public String changePassword(@PathVariable UUID userId, @Valid PasswordForm form,
            BindingResult binding, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.putAll(FormErrors.of(binding));
        if (fieldErrors.isEmpty()) {
            try {
                userAdminService.changePassword(clinicId, userId, passwordEncoder.encode(form.getNewPassword()));
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.password_change", "user");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("user", e.getMessage());
            }
        }
        renderCard(model, clinicId, AdminAccess.membershipId(session), UserForm.empty());
        Toasts.fromErrors(model, fieldErrors, "تم تغيير كلمة المرور");
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
        renderCard(model, clinicId, AdminAccess.membershipId(session), UserForm.empty());
        Toasts.fromErrors(model, fieldErrors, "تم تعليق المستخدم");
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
        renderCard(model, clinicId, AdminAccess.membershipId(session), UserForm.empty());
        Toasts.fromErrors(model, fieldErrors, "تم تفعيل المستخدم");
        return "admin/users :: usersCard";
    }

    @PostMapping("/admin-dashboard/users/{userId}/role")
    public String assignRole(@PathVariable UUID userId, @Valid AssignRoleForm form,
            BindingResult binding, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.putAll(FormErrors.of(binding));
        if (fieldErrors.isEmpty()) {
            try {
                userAdminService.assignRole(clinicId, form.getMembershipId(), form.getRoleCode());
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.assign_role", "user");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("user", e.getMessage());
            }
        }
        renderCard(model, clinicId, AdminAccess.membershipId(session), UserForm.empty());
        Toasts.fromErrors(model, fieldErrors, "تم تغيير الدور");
        return "admin/users :: usersCard";
    }

    private void renderCard(Model model, UUID clinicId, UUID currentMembershipId, UserForm addForm) {
        model.addAttribute("users", userAdminService.list(clinicId));
        model.addAttribute("currentMembershipId", currentMembershipId);
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static class UserForm {
        @NotBlank(message = "اسم المستخدم مطلوب")
        private String username;
        @NotBlank(message = "الاسم الكامل مطلوب")
        private String fullName;
        @Email(regexp = "^$|^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$", message = "صيغة البريد الإلكتروني غير صحيحة")
        private String email;
        @NotBlank(message = "كلمة المرور مطلوبة")
        private String password;

        static UserForm empty() {
            return of("", "", "", "");
        }

        static UserForm of(String username, String fullName, String email, String password) {
            UserForm form = new UserForm();
            form.username = username;
            form.fullName = fullName;
            form.email = email;
            form.password = password;
            return form;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    public static class PasswordForm {
        @NotBlank(message = "كلمة المرور مطلوبة")
        private String newPassword;

        static PasswordForm of(String newPassword) {
            PasswordForm form = new PasswordForm();
            form.newPassword = newPassword;
            return form;
        }

        public String getNewPassword() {
            return newPassword;
        }

        public void setNewPassword(String newPassword) {
            this.newPassword = newPassword;
        }
    }

    public static class AssignRoleForm {
        @NotBlank(message = "الدور مطلوب")
        private String roleCode;
        private UUID membershipId;

        static AssignRoleForm of(String roleCode, UUID membershipId) {
            AssignRoleForm form = new AssignRoleForm();
            form.roleCode = roleCode;
            form.membershipId = membershipId;
            return form;
        }

        public String getRoleCode() {
            return roleCode;
        }

        public void setRoleCode(String roleCode) {
            this.roleCode = roleCode;
        }

        public UUID getMembershipId() {
            return membershipId;
        }

        public void setMembershipId(UUID membershipId) {
            this.membershipId = membershipId;
        }
    }
}