package com.clinicos.ui;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.EmployeeRequest;
import com.clinicos.staff.api.EmployeeService.EmployeeValidationException;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Admin dashboard (ceo) area. The settings tab hosts the employee roster as
 * inline-editable HTMX rows: save posts the row back, archive deletes it.
 * New employee records are created from the Users tab (onboarding): this
 * controller only edits the roster. Each mutation re-renders the whole
 * employee card fragment ({@code admin/employees :: employeesCard}) and
 * writes an activity-log entry.
 */
@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final LayoutModel layoutModel;
    private final EmployeeService employeeService;
    private final ActivityLogService activityLogService;
    private final ClinicSettingsService clinicSettingsService;
    private final UserAdminService userAdminService;

    public AdminController(LayoutModel layoutModel, EmployeeService employeeService,
            ActivityLogService activityLogService, ClinicSettingsService clinicSettingsService,
            UserAdminService userAdminService) {
        this.layoutModel = layoutModel;
        this.employeeService = employeeService;
        this.activityLogService = activityLogService;
        this.clinicSettingsService = clinicSettingsService;
        this.userAdminService = userAdminService;
    }

    @GetMapping("/admin-dashboard")
    public String index() {
        return "redirect:/admin-dashboard/settings";
    }

    @GetMapping("/admin-dashboard/settings")
    public String settings(HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        model.addAttribute("section", NavSectionResolver.sectionByRoute("admin-dashboard"));
        var clinicSettings = clinicSettingsService.get(AdminAccess.clinicId(session));
        model.addAttribute("settings", clinicSettings);
        model.addAttribute("weights", clinicSettings.weights());
        model.addAttribute("weightsSum", ClinicSettingsController.sumWeights(clinicSettings.weights()));
        model.addAttribute("tiers", clinicSettings.tiers());
        model.addAttribute("weightsForm", ClinicSettingsController.WeightsForm.from(clinicSettings.weights()));
        model.addAttribute("volumeForm", ClinicSettingsController.VolumeForm.from(clinicSettings.volumeTarget()));
        model.addAttribute("dutyForm", ClinicSettingsController.DutyForm.from(clinicSettings));
        model.addAttribute("tiersForm", ClinicSettingsController.TiersForm.from(clinicSettings.tiers()));
        renderCard(model, session);
        return "admin/settings";
    }

    @PostMapping("/admin-dashboard/settings/employees/{employeeId}")
    public String updateEmployee(@PathVariable UUID employeeId, @Valid EmployeeForm form,
            BindingResult binding, HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.putAll(FormErrors.of(binding));
        EmployeeRequest request = toRequest(form, fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                employeeService.update(AdminAccess.clinicId(session), employeeId, request);
                activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session), "employee.update", "employee");
            } catch (EmployeeValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            } catch (IllegalArgumentException e) {
                log.warn("updateEmployee failed: employee {} clinic {}", employeeId, AdminAccess.clinicId(session), e);
                fieldErrors.put("employee", e.getMessage());
            }
        }
        RoleChange roleChange = resolveRoleChange(session, employeeId, form.getRoleCode());
        if (fieldErrors.isEmpty() && roleChange != null) {
            try {
                userAdminService.assignRole(AdminAccess.clinicId(session), roleChange.membershipId(), roleChange.roleCode(),
                        AdminAccess.membershipId(session));
            } catch (IllegalArgumentException e) {
                log.warn("assignRole failed: employee {} clinic {}", employeeId, AdminAccess.clinicId(session), e);
                fieldErrors.put("role", e.getMessage());
            }
        }
        renderCard(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم حفظ بيانات الموظف");
        return "admin/employees :: employeesCard";
    }

    @DeleteMapping("/admin-dashboard/settings/employees/{employeeId}")
    public String archiveEmployee(@PathVariable UUID employeeId, HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        var linked = userAdminService.list(AdminAccess.clinicId(session)).stream()
                .filter(user -> employeeId.equals(user.employeeId()))
                .findFirst();
        try {
            employeeService.archive(AdminAccess.clinicId(session), employeeId);
            activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session), "employee.archive", "employee");
            linked.ifPresent(user -> suspendLinkedUser(user, session));
        } catch (IllegalArgumentException e) {
            // not found / already archived / RLS-hidden -- re-render clean card
            log.warn("archiveEmployee failed: employee {} clinic {}", employeeId, AdminAccess.clinicId(session), e);
            fieldErrors.put("employee", e.getMessage());
        }
        renderCard(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم أرشفة الموظف");
        return "admin/employees :: employeesCard";
    }

    private void suspendLinkedUser(UserSummary user, HttpSession session) {
        UUID clinicId = AdminAccess.clinicId(session);
        if ("owner".equals(user.roleCode())
                || user.membershipId().equals(AdminAccess.membershipId(session))) {
            return;
        }
        try {
            userAdminService.suspend(clinicId, user.id(), AdminAccess.membershipId(session));
        } catch (IllegalArgumentException e) {
            log.warn("archive suspend skipped: user {} clinic {}", user.id(), clinicId, e);
        }
    }

    private boolean canAccessDashboard(HttpSession session) {
        return AdminAccess.canDashboard(layoutModel, session);
    }

    private void renderCard(Model model, HttpSession session) {
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("employees", employeeService.list(clinicId));
        model.addAttribute("employeeRoles", employeeRoles(userAdminService.list(clinicId)));
        model.addAttribute("actorRole", AdminAccess.roleCode(session));
    }

    private static Map<UUID, UserSummary> employeeRoles(java.util.List<UserSummary> users) {
        Map<UUID, UserSummary> roles = new HashMap<>();
        for (UserSummary user : users) {
            if (user.employeeId() != null) {
                roles.put(user.employeeId(), user);
            }
        }
        return roles;
    }

    private record RoleChange(UUID membershipId, String roleCode) {
    }

    private RoleChange resolveRoleChange(HttpSession session, UUID employeeId, String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return null;
        }
        for (UserSummary user : userAdminService.list(AdminAccess.clinicId(session))) {
            if (employeeId.equals(user.employeeId())) {
                if ("owner".equals(user.roleCode()) || user.roleCode().equals(roleCode)) {
                    return null;
                }
                return new RoleChange(user.membershipId(), roleCode);
            }
        }
        return null;
    }

    static EmployeeRequest toRequest(EmployeeForm form, Map<String, String> fieldErrors) {
        boolean isCustomShift = form.isCustomShift();
        if (form.getName() == null || form.getName().isBlank()) {
            fieldErrors.put("name", "اسم الموظف مطلوب");
        }
        var basePay = FormParsing.parseAmount(form.getBasePay(), "basePay", fieldErrors, "المرتب الأساسي غير صحيح");
        var maxIncentive = FormParsing.parseAmount(form.getMaxIncentive(), "maxIncentive", fieldErrors, "الحافز الكامل غير صحيح");
        LocalTime parsedShiftStart = null;
        LocalTime parsedShiftEnd = null;
        if (isCustomShift) {
            parsedShiftStart = FormParsing.parseTime(form.getShiftStart(), "shift", fieldErrors);
            parsedShiftEnd = FormParsing.parseTime(form.getShiftEnd(), "shift", fieldErrors);
        }
        return new EmployeeRequest(
                form.getName() == null ? "" : form.getName().trim(),
                basePay,
                maxIncentive,
                parsedShiftStart,
                parsedShiftEnd,
                isCustomShift,
                null);
    }

    public static class EmployeeForm {
        @NotBlank(message = "اسم الموظف مطلوب")
        private String name;
        private String roleCode;
        private String basePay;
        private String maxIncentive;
        private boolean customShift;
        private String shiftStart;
        private String shiftEnd;

        static EmployeeForm empty() {
            return of("", null, "", "", false, "", "");
        }

        static EmployeeForm of(String name, String roleCode, String basePay, String maxIncentive,
                boolean customShift, String shiftStart, String shiftEnd) {
            EmployeeForm form = new EmployeeForm();
            form.name = name;
            form.roleCode = roleCode;
            form.basePay = basePay;
            form.maxIncentive = maxIncentive;
            form.customShift = customShift;
            form.shiftStart = shiftStart;
            form.shiftEnd = shiftEnd;
            return form;
        }

        public String getName() {
            return name;
        }

        public String getRoleCode() {
            return roleCode;
        }

        public String getBasePay() {
            return basePay;
        }

        public String getMaxIncentive() {
            return maxIncentive;
        }

        public boolean isCustomShift() {
            return customShift;
        }

        public String getShiftStart() {
            return shiftStart;
        }

        public String getShiftEnd() {
            return shiftEnd;
        }

        public void setName(String name) {
            this.name = name;
        }

        public void setRoleCode(String roleCode) {
            this.roleCode = roleCode;
        }

        public void setBasePay(String basePay) {
            this.basePay = basePay;
        }

        public void setMaxIncentive(String maxIncentive) {
            this.maxIncentive = maxIncentive;
        }

        public void setCustomShift(boolean customShift) {
            this.customShift = customShift;
        }

        public void setShiftStart(String shiftStart) {
            this.shiftStart = shiftStart;
        }

        public void setShiftEnd(String shiftEnd) {
            this.shiftEnd = shiftEnd;
        }
    }

}