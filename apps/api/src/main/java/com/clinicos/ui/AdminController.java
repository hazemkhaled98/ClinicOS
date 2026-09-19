package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.academy.AcademyService;
import com.clinicos.academy.AcademyService.Actor;
import com.clinicos.evaluation.api.EvaluationService;
import com.clinicos.evaluation.api.EvaluationService.TeamScore;
import com.clinicos.shared.ActivityLogService.Entry;
import com.clinicos.shared.jooq.enums.AcademyAudience;
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
    private final WorkCalendarService workCalendarService;
    private final UserAdminService userAdminService;
    private final EvaluationService evaluationService;
    private final AcademyService academyService;

    public AdminController(LayoutModel layoutModel, EmployeeService employeeService,
            ActivityLogService activityLogService, ClinicSettingsService clinicSettingsService,
            WorkCalendarService workCalendarService, UserAdminService userAdminService,
            EvaluationService evaluationService, AcademyService academyService) {
        this.layoutModel = layoutModel;
        this.employeeService = employeeService;
        this.activityLogService = activityLogService;
        this.clinicSettingsService = clinicSettingsService;
        this.workCalendarService = workCalendarService;
        this.userAdminService = userAdminService;
        this.evaluationService = evaluationService;
        this.academyService = academyService;
    }

    @GetMapping("/admin-dashboard")
    public String index() {
        return "redirect:/admin-dashboard/overview";
    }

    @GetMapping("/admin-dashboard/overview")
    public String overview(@RequestParam(required = false) String month, HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        model.addAttribute("section", NavSectionResolver.sectionByRoute("admin-dashboard"));
        YearMonth selected = parseMonth(month);
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("month", selected.toString());
        model.addAttribute("currentMonth", YearMonth.now().toString());
        model.addAttribute("prevMonth", selected.minusMonths(1).toString());
        model.addAttribute("nextMonth", selected.plusMonths(1).toString());
        model.addAttribute("nextDisabled", selected.equals(YearMonth.now()));
        model.addAttribute("volumePace", evaluationService.volumePace(clinicId, selected));
        model.addAttribute("volumeForm", new VolumeCapForm(selected));
        List<TeamScore> team = evaluationService.teamScores(clinicId, selected);
        model.addAttribute("teamScores", team);
        model.addAttribute("headcount", team.size());
        model.addAttribute("avgScore", averageScore(team));
        return "admin/dashboard-page";
    }

    @GetMapping("/admin-dashboard/staff")
    public String staff(@RequestParam(required = false) String employee, @RequestParam(required = false) String month,
            HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        List<EmployeeService.Employee> employees = employeeService.list(clinicId);
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        model.addAttribute("section", NavSectionResolver.sectionByRoute("admin-dashboard"));
        model.addAttribute("employees", employees);
        model.addAttribute("month", parseMonth(month).toString());
        UUID employeeId = parseUuid(employee);
        if (employeeId != null && employees.stream().anyMatch(item -> item.id().equals(employeeId))) {
            Actor actor = new Actor(AdminAccess.membershipId(session), AdminAccess.roleCode(session),
                    sessionEmployeeId(session, clinicId));
            try {
                AcademyAudience audience = academyService.audienceOf(clinicId, employeeId);
                var track = academyService.traineeCurriculum(clinicId, actor, employeeId, audience);
                long completed = track.units().stream().filter(unit -> "done".equals(unit.status())).count();
                model.addAttribute("track", track);
                model.addAttribute("completedUnits", completed);
                model.addAttribute("totalUnits", track.units().size());
                model.addAttribute("selectedEmployee", employeeId);
                model.addAttribute("evaluation", evaluationService.evaluate(clinicId, employeeId, parseMonth(month)));
            } catch (IllegalArgumentException e) {
                return "redirect:/admin-dashboard/staff";
            }
        }
        return "admin/employee-profile-page";
    }

    @GetMapping("/admin-dashboard/activity")
    public String activity(@RequestParam(required = false) String day, @RequestParam(required = false) String category,
            HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        LocalDate selected = parseDay(day);
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        model.addAttribute("section", NavSectionResolver.sectionByRoute("admin-dashboard"));
        model.addAttribute("day", selected);
        model.addAttribute("category", category == null ? "all" : category);
        model.addAttribute("categories", activityCategories());
        model.addAttribute("labels", activityLabels());
        model.addAttribute("entries", activityLogService.forDay(AdminAccess.clinicId(session), selected,
                activityActions(category)));
        return "admin/activity-page";
    }

    private UUID sessionEmployeeId(HttpSession session, UUID clinicId) {
        EmployeeService.Employee employee = employeeService.findByMembership(clinicId, AdminAccess.membershipId(session));
        return employee == null ? null : employee.id();
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate parseDay(String value) {
        try {
            return value == null || value.isBlank() ? LocalDate.now() : LocalDate.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDate.now();
        }
    }

    private static Map<String, String> activityCategories() {
        return Map.of("all", "الكل", "auth", "دخول النظام", "finance", "تعديلات مالية", "operations", "مواعيد وكشوفات");
    }

    private static Map<String, String> activityLabels() {
        return Map.ofEntries(
                Map.entry("login", "دخول النظام"), Map.entry("signup", "إنشاء العيادة"),
                Map.entry("volume.record", "تسجيل حجم الإنتاج"), Map.entry("eval.override", "تعديل حد التقييم"),
                Map.entry("eval.unlock", "فتح شهر للتقييم"), Map.entry("employee.update", "تعديل موظف"),
                Map.entry("inventory.issue", "صرف مخزون"), Map.entry("inventory.receive", "استلام مخزون"),
                Map.entry("permissions.update", "تحديث الصلاحيات"), Map.entry("user.create", "إنشاء مستخدم"),
                Map.entry("user.suspend", "تعليق مستخدم"), Map.entry("user.reactivate", "إعادة تفعيل مستخدم"),
                Map.entry("user.password_change", "تغيير كلمة المرور"), Map.entry("user.assign_role", "تعديل الدور"));
    }

    private static String activityActions(String category) {
        return switch (category == null ? "all" : category) {
        case "auth" -> "login,signup,user.create,user.suspend,user.reactivate,user.password_change,user.assign_role,permissions.update";
        case "finance" -> "volume.record,eval.override,eval.unlock,employee.update,inventory.issue,inventory.receive,gamification";
        case "operations" -> "selfcheck,task,assignment,prep,academy,eval.approve,eval.reject,eval.assign";
        default -> "all";
        };
    }

    @PostMapping("/admin-dashboard/overview/volume")
    public String recordVolume(VolumeCapForm form, HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        YearMonth month = parseMonth(form.getMonth());
        Map<String, String> fieldErrors = new HashMap<>();
        BigDecimal amount = FormParsing.parseAmount(form.getAmount(), "amount", fieldErrors, "حجم الإنتاج غير صحيح");
        if (fieldErrors.isEmpty()) {
            try {
                evaluationService.recordVolume(AdminAccess.clinicId(session), month, amount,
                        AdminAccess.membershipId(session));
                activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session),
                    "volume.record", "operations_volume");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("amount", e.getMessage());
            }
        }
        Toasts.fromErrors(model, fieldErrors, "تم تسجيل حجم الإنتاج");
        renderVolumeCard(model, session, month);
        return "admin/dashboard :: volumeCard";
    }

    private void renderVolumeCard(Model model, HttpSession session, YearMonth month) {
        model.addAttribute("volumePace", evaluationService.volumePace(AdminAccess.clinicId(session), month));
        model.addAttribute("volumeForm", new VolumeCapForm(month));
        model.addAttribute("month", month.toString());
        model.addAttribute("currentMonth", YearMonth.now().toString());
        model.addAttribute("nextDisabled", month.equals(YearMonth.now()));
    }

    private static BigDecimal averageScore(List<TeamScore> team) {
        List<BigDecimal> scored = team.stream().map(TeamScore::finalScore)
                .filter(java.util.Objects::nonNull).toList();
        if (scored.isEmpty()) {
            return null;
        }
        return scored.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(scored.size()), 1, java.math.RoundingMode.HALF_UP);
    }

    static YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return YearMonth.now();
        }
    }

    static class VolumeCapForm {
        private String month;
        private String amount;

        VolumeCapForm() {
        }

        VolumeCapForm(YearMonth month) {
            this.month = month.toString();
            this.amount = "";
        }

        public String getMonth() {
            return month;
        }

        public void setMonth(String month) {
            this.month = month;
        }

        public String getAmount() {
            return amount;
        }

        public void setAmount(String amount) {
            this.amount = amount;
        }
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
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("weekdaysForm", ClinicSettingsController.WeekdaysForm.from(workCalendarService.workingWeekdays(clinicId)));
        model.addAttribute("holidaysForm", new ClinicSettingsController.HolidayForm());
        model.addAttribute("holidays", workCalendarService.listHolidays(clinicId));
        model.addAttribute("employees", employeeService.list(clinicId));
        renderCard(model, session, userAdminService.list(clinicId));
        return "admin/settings";
    }

    @PostMapping("/admin-dashboard/settings/employees/{employeeId}")
    public String updateEmployee(@PathVariable UUID employeeId, @Valid EmployeeForm form,
            BindingResult binding, HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        var users = userAdminService.list(AdminAccess.clinicId(session));
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.putAll(FormErrors.of(binding));
        if (!isManageableBy(AdminAccess.roleCode(session), linkedUser(users, employeeId))) {
            fieldErrors.put("employee", "لا يمكنك تعديل بيانات هذا الموظف");
        }
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
        RoleChange roleChange = resolveRoleChange(users, employeeId, form.getRoleCode());
        if (fieldErrors.isEmpty() && roleChange != null) {
            try {
                userAdminService.assignRole(AdminAccess.clinicId(session), roleChange.membershipId(), roleChange.roleCode(),
                        AdminAccess.membershipId(session));
            } catch (IllegalArgumentException e) {
                log.warn("assignRole failed: employee {} clinic {}", employeeId, AdminAccess.clinicId(session), e);
                fieldErrors.put("role", e.getMessage());
            }
        }
        renderCard(model, session, fieldErrors.isEmpty() ? userAdminService.list(AdminAccess.clinicId(session)) : users);
        Toasts.fromErrors(model, fieldErrors, "تم حفظ بيانات الموظف");
        return "admin/employees :: employeesCard";
    }

    @DeleteMapping("/admin-dashboard/settings/employees/{employeeId}")
    public String archiveEmployee(@PathVariable UUID employeeId, HttpSession session, Model model) {
        if (!canAccessDashboard(session)) {
            return "redirect:/";
        }
        var users = userAdminService.list(AdminAccess.clinicId(session));
        Map<String, String> fieldErrors = new HashMap<>();
        var linked = Optional.ofNullable(linkedUser(users, employeeId));
        if (!isManageableBy(AdminAccess.roleCode(session), linked.orElse(null))) {
            fieldErrors.put("employee", "لا يمكنك أرشفة هذا الموظف");
            renderCard(model, session, users);
            Toasts.fromErrors(model, fieldErrors, "تم أرشفة الموظف");
            return "admin/employees :: employeesCard";
        }
        try {
            employeeService.archive(AdminAccess.clinicId(session), employeeId);
            activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session), "employee.archive", "employee");
            boolean suspended = linked.map(user -> suspendLinkedUser(user, session)).orElse(true);
            if (!suspended) {
                fieldErrors.put("employee", "تم أرشفة الموظف، لكن تعليق حساب الدخول فشل");
            }
        } catch (IllegalArgumentException e) {
            // not found / already archived / RLS-hidden -- re-render clean card
            log.warn("archiveEmployee failed: employee {} clinic {}", employeeId, AdminAccess.clinicId(session), e);
            fieldErrors.put("employee", e.getMessage());
        }
        renderCard(model, session, fieldErrors.isEmpty() ? userAdminService.list(AdminAccess.clinicId(session)) : users);
        Toasts.fromErrors(model, fieldErrors, "تم أرشفة الموظف");
        return "admin/employees :: employeesCard";
    }

    private boolean suspendLinkedUser(UserSummary user, HttpSession session) {
        UUID clinicId = AdminAccess.clinicId(session);
        if ("owner".equals(user.roleCode())
                || user.membershipId().equals(AdminAccess.membershipId(session))) {
            return true;
        }
        try {
            userAdminService.suspend(clinicId, user.id(), AdminAccess.membershipId(session));
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("archive suspend failed: user {} clinic {}", user.id(), clinicId, e);
            return false;
        }
    }

    private boolean canAccessDashboard(HttpSession session) {
        return AdminAccess.canDashboard(layoutModel, session);
    }

    private void renderCard(Model model, HttpSession session, List<UserSummary> users) {
        UUID clinicId = AdminAccess.clinicId(session);
        Map<UUID, UserSummary> employeeRoles = employeeRoles(users);
        String actorRole = AdminAccess.roleCode(session);
        var employees = employeeService.list(clinicId).stream()
                .filter(employee -> isManageableBy(actorRole, employeeRoles.get(employee.id())))
                .toList();
        model.addAttribute("employees", employees);
        model.addAttribute("employeeRoles", employeeRoles);
        model.addAttribute("actorRole", actorRole);
    }

    private static UserSummary linkedUser(List<UserSummary> users, UUID employeeId) {
        return users.stream()
                .filter(user -> employeeId.equals(user.employeeId()))
                .findFirst()
                .orElse(null);
    }

    private static boolean isManageableBy(String actorRole, UserSummary linkedUser) {
        if ("owner".equals(actorRole) || linkedUser == null) {
            return true;
        }
        String employeeRole = linkedUser.roleCode();
        return !"owner".equals(employeeRole) && !"manager".equals(employeeRole);
    }

    private static Map<UUID, UserSummary> employeeRoles(List<UserSummary> users) {
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

    private static RoleChange resolveRoleChange(List<UserSummary> users, UUID employeeId, String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return null;
        }
        for (UserSummary user : users) {
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
