package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
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

import jakarta.servlet.http.HttpSession;

@Controller
public class UserAdminController {

    private final LayoutModel layoutModel;
    private final UserAdminService userAdminService;
    private final EmployeeService employeeService;
    private final ActivityLogService activityLogService;
    private final Argon2PasswordEncoder passwordEncoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    public UserAdminController(LayoutModel layoutModel, UserAdminService userAdminService,
            EmployeeService employeeService, ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.userAdminService = userAdminService;
        this.employeeService = employeeService;
        this.activityLogService = activityLogService;
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
    @Transactional
    public String createUser(UserForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        validate(form, fieldErrors);
        String membershipRoleCode = null;
        EmployeeRequest employeeRequest = null;
        if (fieldErrors.isEmpty()) {
            if (Boolean.TRUE.equals(form.createEmployee())) {
                membershipRoleCode = form.staffRoleCode();
                employeeRequest = toEmployeeRequest(form, fieldErrors);
            } else {
                membershipRoleCode = form.roleCode();
            }
        }
        if (fieldErrors.isEmpty()) {
            try {
                UserSummary created = userAdminService.create(clinicId, new UserCreateRequest(
                        form.username().trim(), form.fullName().trim(), form.email(), passwordEncoder.encode(form.password())));
                if (employeeRequest != null) {
                    Employee employee = employeeService.create(clinicId, employeeRequest);
                    userAdminService.linkEmployee(clinicId, created.membershipId(), employee.id());
                }
                if (membershipRoleCode != null && !"receptionist".equals(membershipRoleCode)) {
                    userAdminService.assignRole(clinicId, created.membershipId(), membershipRoleCode);
                }
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.create", "user");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("username", e.getMessage());
            } catch (EmployeeValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
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

    @PostMapping("/admin-dashboard/users/memberships/{membershipId}/link-employee")
    public String linkEmployee(@PathVariable UUID membershipId, @RequestParam(required = false) UUID employeeId,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        if (employeeId != null) {
            try {
                userAdminService.linkEmployee(clinicId, membershipId, employeeId);
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "user.link_employee", "user");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("user", e.getMessage());
            }
        }
        renderCard(model, clinicId, AdminAccess.membershipId(session), fieldErrors,
                fieldErrors.isEmpty() ? null : "link", UserForm.empty());
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تم ربط الموظف");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/users :: usersCard";
    }

    private void renderCard(Model model, UUID clinicId, UUID currentMembershipId, Map<String, String> fieldErrors,
            String errorScope, UserForm addForm) {
        java.util.List<Employee> employees = employeeService.list(clinicId);
        model.addAttribute("users", userAdminService.list(clinicId));
        model.addAttribute("employees", employees);
        model.addAttribute("currentMembershipId", currentMembershipId);
        model.addAttribute("employeeNames", employeeNames(employees));
        model.addAttribute("userErrors", fieldErrors == null ? Map.of() : fieldErrors);
        model.addAttribute("userErrorScope", errorScope);
        model.addAttribute("addForm", addForm);
        model.addAttribute("roleNames", roleNames());
    }

    private static Map<UUID, String> employeeNames(java.util.List<Employee> employees) {
        Map<UUID, String> names = new HashMap<>();
        for (Employee employee : employees) {
            names.put(employee.id(), employee.name());
        }
        return names;
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

    private static EmployeeRequest toEmployeeRequest(UserForm form, Map<String, String> errors) {
        StaffRole role = null;
        if (form.staffRoleCode() == null || form.staffRoleCode().isBlank()) {
            errors.put("staffRole", "المسمى الوظيفي مطلوب");
        } else {
            try {
                role = StaffRole.fromCode(form.staffRoleCode());
            } catch (IllegalArgumentException e) {
                errors.put("staffRole", e.getMessage());
            }
        }
        boolean isCustomShift = Boolean.TRUE.equals(form.customShift());
        BigDecimal basePay = FormParsing.parseAmount(form.basePay(), "basePay", errors, "المرتب الأساسي غير صحيح");
        BigDecimal maxIncentive = FormParsing.parseAmount(form.maxIncentive(), "maxIncentive", errors,
                "الحافز الكامل غير صحيح");
        LocalTime shiftStart = null;
        LocalTime shiftEnd = null;
        if (isCustomShift) {
            shiftStart = FormParsing.parseTime(form.shiftStart(), "shift", errors);
            shiftEnd = FormParsing.parseTime(form.shiftEnd(), "shift", errors);
        }
        return new EmployeeRequest(form.fullName().trim(), role, basePay, maxIncentive, shiftStart, shiftEnd,
                isCustomShift, null);
    }

    public record UserForm(String username, String fullName, String email, String password,
            Boolean createEmployee, String roleCode, String staffRoleCode,
            String basePay, String maxIncentive, Boolean customShift,
            String shiftStart, String shiftEnd) {

        static UserForm empty() {
            return of("", "", "", "");
        }

        static UserForm of(String username, String fullName, String email, String password) {
            return new UserForm(username, fullName, email, password, false, "receptionist",
                    "assistant", "", "", false, "", "");
        }
    }
}