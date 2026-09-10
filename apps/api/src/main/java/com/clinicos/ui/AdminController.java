package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.EmployeeRequest;
import com.clinicos.staff.api.EmployeeService.EmployeeValidationException;
import com.clinicos.ui.nav.NavSectionResolver;
import com.clinicos.ui.nav.NavSection;

import jakarta.servlet.http.HttpSession;

/**
 * Admin dashboard (ceo) area. The settings tab hosts the employee roster as
 * inline-editable HTMX rows: save posts the row back, archive deletes it, the
 * add form at the bottom creates a new employee. Each mutation re-renders the
 * whole employee card fragment ({@code admin/employees :: employeesCard}) and
 * writes an activity-log entry.
 */
@Controller
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final LayoutModel layoutModel;
    private final EmployeeService employeeService;
    private final ActivityLogService activityLogService;
    private final ClinicSettingsService clinicSettingsService;

    public AdminController(LayoutModel layoutModel, EmployeeService employeeService,
            ActivityLogService activityLogService, ClinicSettingsService clinicSettingsService) {
        this.layoutModel = layoutModel;
        this.employeeService = employeeService;
        this.activityLogService = activityLogService;
        this.clinicSettingsService = clinicSettingsService;
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
        renderCard(model, session, Map.of(), null, null, EmployeeForm.empty());
        return "admin/settings";
    }

    @PostMapping("/admin-dashboard/settings/employees")
    public String createEmployee(EmployeeForm form, HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        EmployeeRequest request = form.toRequest(fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                employeeService.create(AdminAccess.clinicId(session), request);
                activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session), "employee.create", "employee");
            } catch (EmployeeValidationException e) {
                fieldErrors.putAll(e.fieldErrors());
            }
        }
        renderCard(model, session, fieldErrors, fieldErrors.isEmpty() ? null : "add", null, form);
        return "admin/employees :: employeesCard";
    }

    @PostMapping("/admin-dashboard/settings/employees/{employeeId}")
    public String updateEmployee(@PathVariable UUID employeeId, EmployeeForm form,
            HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        EmployeeRequest request = form.toRequest(fieldErrors);
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
        renderCard(model, session, fieldErrors, fieldErrors.isEmpty() ? null : "edit", employeeId, form);
        return "admin/employees :: employeesCard";
    }

    @DeleteMapping("/admin-dashboard/settings/employees/{employeeId}")
    public String archiveEmployee(@PathVariable UUID employeeId, HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            employeeService.archive(AdminAccess.clinicId(session), employeeId);
            activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session), "employee.archive", "employee");
        } catch (IllegalArgumentException e) {
            // not found / already archived / RLS-hidden -- re-render clean card
            log.warn("archiveEmployee failed: employee {} clinic {}", employeeId, AdminAccess.clinicId(session), e);
            fieldErrors.put("employee", e.getMessage());
        }
        renderCard(model, session, fieldErrors, fieldErrors.isEmpty() ? null : "archive", null, EmployeeForm.empty());
        return "admin/employees :: employeesCard";
    }

    private boolean canAccessDashboard(HttpSession session) {
        return layoutModel.forRequest(session, "admin-dashboard")
                .nav()
                .contains(NavSectionResolver.sectionByRoute("admin-dashboard"));
    }

    private void renderCard(Model model, HttpSession session, Map<String, String> fieldErrors,
            String errorScope, UUID errorRow, EmployeeForm addForm) {
        model.addAttribute("employees", employeeService.list(AdminAccess.clinicId(session)));
        model.addAttribute("employeeErrors", fieldErrors);
        model.addAttribute("employeeErrorScope", errorScope);
        model.addAttribute("employeeErrorRow", errorRow);
        model.addAttribute("addForm", addForm);
    }

    public record EmployeeForm(String name, String staffRoleCode, String basePay, String maxIncentive,
            Boolean customShift, String shiftStart, String shiftEnd) {

        static EmployeeForm empty() {
            return new EmployeeForm("", "assistant", "", "", false, "", "");
        }

        EmployeeRequest toRequest(Map<String, String> fieldErrors) {
            boolean isCustomShift = Boolean.TRUE.equals(customShift);
            if (name == null || name.isBlank()) {
                fieldErrors.put("name", "اسم الموظف مطلوب");
            }
            com.clinicos.staff.api.EmployeeService.StaffRole role = null;
            if (staffRoleCode == null || staffRoleCode.isBlank()) {
                fieldErrors.put("staffRole", "المسمى الوظيفي مطلوب");
            } else {
                try {
                    role = com.clinicos.staff.api.EmployeeService.StaffRole.fromCode(staffRoleCode);
                } catch (IllegalArgumentException e) {
                    fieldErrors.put("staffRole", e.getMessage());
                }
            }
            BigDecimal parsedBasePay = parseAmount(basePay, "basePay", fieldErrors, "المرتب الأساسي غير صحيح");
            BigDecimal parsedMaxIncentive = parseAmount(maxIncentive, "maxIncentive", fieldErrors,
                    "الحافز الكامل غير صحيح");
            LocalTime parsedShiftStart = null;
            LocalTime parsedShiftEnd = null;
            if (isCustomShift) {
                parsedShiftStart = parseTime(shiftStart, "shift", fieldErrors);
                parsedShiftEnd = parseTime(shiftEnd, "shift", fieldErrors);
            }
            return new EmployeeRequest(
                    name == null ? "" : name.trim(),
                    role,
                    parsedBasePay,
                    parsedMaxIncentive,
                    parsedShiftStart,
                    parsedShiftEnd,
                    isCustomShift,
                    null);
        }
    }

    private static BigDecimal parseAmount(String value, String key, Map<String, String> errors, String message) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            errors.put(key, message);
            return null;
        }
    }

    private static LocalTime parseTime(String value, String key, Map<String, String> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            errors.put(key, "وقت غير صحيح");
            return null;
        }
    }
}
