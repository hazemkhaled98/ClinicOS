package com.clinicos.ui;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.evaluation.api.EvaluationService;
import com.clinicos.evaluation.api.EvaluationService.ComponentScore;
import com.clinicos.evaluation.api.EvaluationService.EvaluationConflictException;
import com.clinicos.evaluation.api.EvaluationService.MonthlyEvaluation;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.DailyWorkService;
import com.clinicos.staff.api.DailyWorkService.CompletionRow;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.TaskAssignmentService;
import com.clinicos.staff.api.TaskAssignmentService.Assignment;
import com.clinicos.staff.api.TaskAssignmentService.AssignmentForm;
import com.clinicos.staff.api.TaskAssignmentService.Proposer;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

@Controller
public class EvaluationController {

    private static final String GRID = "evaluation :: reviewGrid";

    private final LayoutModel layoutModel;
    private final EmployeeService employeeService;
    private final EvaluationService evaluationService;
    private final DailyWorkService dailyWorkService;
    private final TaskAssignmentService assignmentService;
    private final ActivityLogService activityLogService;

    public EvaluationController(LayoutModel layoutModel, EmployeeService employeeService,
            EvaluationService evaluationService, DailyWorkService dailyWorkService,
            TaskAssignmentService assignmentService, ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.employeeService = employeeService;
        this.evaluationService = evaluationService;
        this.dailyWorkService = dailyWorkService;
        this.assignmentService = assignmentService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/evaluation")
    public String evaluation(@RequestParam(required = false) String month,
            @RequestParam(required = false) String employee, HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        YearMonth selectedMonth = parseMonth(month);
        List<Employee> employees = employeeService.list(clinicId);
        UUID selectedId = parseEmployee(employee, employees);

        model.addAttribute("layout", layoutModel.forRequest(session, "evaluation"));
        model.addAttribute("month", selectedMonth.toString());
        model.addAttribute("currentMonth", YearMonth.now().toString());
        model.addAttribute("prevMonth", selectedMonth.minusMonths(1).toString());
        model.addAttribute("nextMonth", selectedMonth.plusMonths(1).toString());
        model.addAttribute("nextDisabled", selectedMonth.equals(YearMonth.now()));
        model.addAttribute("employees", employees);
        model.addAttribute("selectedId", selectedId);

        if (selectedId != null) {
            renderGrid(model, clinicId, selectedId, selectedMonth);
        }
        return "evaluation-page";
    }

    @PostMapping("/evaluation/{employeeId}/completion/{dailyRecordId}/{taskId}/approve")
    public String approveCompletion(@PathVariable UUID employeeId, @PathVariable UUID dailyRecordId,
            @PathVariable UUID taskId, @RequestParam String month, HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> errors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        try {
            dailyWorkService.approveReview(clinicId, dailyRecordId, taskId,
                    AdminAccess.membershipId(session));
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "eval.approve", "daily_task_completion");
        } catch (IllegalArgumentException e) {
            errors.put("completion", e.getMessage());
        }
        renderGrid(model, clinicId, employeeId, parseMonth(month));
        Toasts.fromErrors(model, errors, "تم اعتماد الإنجاز ✔");
        return GRID;
    }

    @PostMapping("/evaluation/{employeeId}/completion/{dailyRecordId}/{taskId}/reject")
    public String rejectCompletion(@PathVariable UUID employeeId, @PathVariable UUID dailyRecordId,
            @PathVariable UUID taskId, @RequestParam String month, @RequestParam(required = false) String reason,
            HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> errors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        try {
            dailyWorkService.rejectReview(clinicId, dailyRecordId, taskId,
                    AdminAccess.membershipId(session), reason);
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "eval.reject", "daily_task_completion");
        } catch (IllegalArgumentException e) {
            errors.put("completion", e.getMessage());
        }
        renderGrid(model, clinicId, employeeId, parseMonth(month));
        Toasts.fromErrors(model, errors, "تم رفض الإنجاز");
        return GRID;
    }

    @PostMapping("/evaluation/{employeeId}/assignment/{assignmentId}/approve")
    public String approveAssignment(@PathVariable UUID employeeId, @PathVariable UUID assignmentId,
            @RequestParam String month, HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> errors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        try {
            assignmentService.approve(clinicId, assignmentId, AdminAccess.membershipId(session));
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "eval.approve", "task_assignment");
        } catch (IllegalArgumentException e) {
            errors.put("assignment", e.getMessage());
        }
        renderGrid(model, clinicId, employeeId, parseMonth(month));
        Toasts.fromErrors(model, errors, "تم اعتماد المهمة ✔");
        return GRID;
    }

    @PostMapping("/evaluation/{employeeId}/assignment/{assignmentId}/reject")
    public String rejectAssignment(@PathVariable UUID employeeId, @PathVariable UUID assignmentId,
            @RequestParam String month, @RequestParam(required = false) String reason,
            HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> errors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        try {
            assignmentService.reject(clinicId, assignmentId, AdminAccess.membershipId(session), reason);
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "eval.reject", "task_assignment");
        } catch (IllegalArgumentException e) {
            errors.put("assignment", e.getMessage());
        }
        renderGrid(model, clinicId, employeeId, parseMonth(month));
        Toasts.fromErrors(model, errors, "تم رفض المهمة");
        return GRID;
    }

    @PostMapping("/evaluation/{employeeId}/assign")
    public String assign(@PathVariable UUID employeeId, @RequestParam String month,
            @RequestParam String name, @RequestParam(required = false) String dueDate,
            HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> errors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        if (name != null && !name.isBlank()) {
            try {
                LocalDate due = dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate.trim());
                assignmentService.propose(clinicId, employeeId,
                        new AssignmentForm(employeeId, name.strip(), due), Proposer.MANAGER);
                activityLogService.log(clinicId, AdminAccess.membershipId(session),
                        "eval.assign", "task_assignment");
            } catch (RuntimeException e) {
                errors.put("assignment", e.getMessage());
            }
        } else {
            errors.put("assignment", "اسم المهمة مطلوب");
        }
        renderGrid(model, clinicId, employeeId, parseMonth(month));
        Toasts.fromErrors(model, errors, "تم تعيين المهمة ✔");
        return GRID;
    }

    @PostMapping("/evaluation/{employeeId}/override")
    public String override(@PathVariable UUID employeeId, @RequestParam String month,
            @RequestParam String category, @RequestParam String floorValue,
            HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> errors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        try {
            Category cat = Category.fromCode(category.trim());
            if (cat == null) {
                throw new IllegalArgumentException("فئة غير معروفة");
            }
            BigDecimal floor = new BigDecimal(floorValue.trim());
            evaluationService.setOverride(clinicId, employeeId, parseMonth(month), cat, floor,
                    AdminAccess.membershipId(session));
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "eval.override", "performance_override");
        } catch (IllegalArgumentException e) {
            errors.put("override", e.getMessage());
        }
        renderGrid(model, clinicId, employeeId, parseMonth(month));
        Toasts.fromErrors(model, errors, "تم حفظ الحد الأدنى ✔");
        return GRID;
    }

    @PostMapping("/evaluation/{employeeId}/unlock")
    public String unlock(@PathVariable UUID employeeId, @RequestParam String month,
            HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> errors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        try {
            evaluationService.unlock(clinicId, employeeId, parseMonth(month),
                    AdminAccess.membershipId(session));
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "eval.unlock", "evaluation_snapshot");
        } catch (EvaluationConflictException e) {
            errors.put("unlock", e.getMessage());
        }
        renderGrid(model, clinicId, employeeId, parseMonth(month));
        Toasts.fromErrors(model, errors, "تم فتح الشهر لإعادة التقييم ✔");
        return GRID;
    }

    private void renderGrid(Model model, UUID clinicId, UUID employeeId, YearMonth month) {
        model.addAttribute("month", month.toString());
        model.addAttribute("selectedId", employeeId);
        model.addAttribute("isClosed", month.isBefore(YearMonth.now()));
        EvaluationView view = buildView(clinicId, employeeId, month);
        model.addAttribute("view", view);
        model.addAttribute("completions", buildCompletions(clinicId, employeeId, month));
        model.addAttribute("assignments", buildAssignments(clinicId, employeeId, month));
        model.addAttribute("categoryOptions", List.of(Category.values()).stream()
                .map(c -> new CategoryOption(c.code(), c.arabicName()))
                .toList());
    }

    private List<CompletionView> buildCompletions(UUID clinicId, UUID employeeId, YearMonth month) {
        return dailyWorkService.listForMonth(clinicId, employeeId, month).stream()
                .map(c -> new CompletionView(c.dailyRecordId(), c.taskDefinitionId(), c.taskName(),
                        c.workDate().toString(), c.reviewStatus(), reviewLabel(c.reviewStatus()),
                        c.reviewReason(), c.photoId(), c.requiresPhoto()))
                .toList();
    }

    private List<AssignmentView> buildAssignments(UUID clinicId, UUID employeeId, YearMonth month) {
        return assignmentService.listForMonth(clinicId, employeeId, month).stream()
                .map(a -> new AssignmentView(a.id(), a.name(),
                        a.dueDate() == null ? null : a.dueDate().toString(),
                        a.status(), assignmentLabel(a), a.doneAt(), a.proofPhotoId()))
                .toList();
    }

    private static String reviewLabel(String status) {
        return switch (status == null ? "" : status) {
            case "approved" -> "مُعتمد";
            case "rejected" -> "مرفوض";
            default -> "بانتظار المراجعة";
        };
    }

    private static String assignmentLabel(Assignment a) {
        if (a.doneAt() != null && "pending".equals(a.status())) {
            return "مُسلَّم — بانتظار الاعتماد";
        }
        return switch (a.status()) {
            case "pending" -> "بانتظار الاعتماد";
            case "rejected" -> "مرفوض";
            default -> "مُعتمد";
        };
    }

    private boolean canView(HttpSession session) {
        return layoutModel.forRequest(session, "evaluation")
                .nav()
                .contains(NavSectionResolver.sectionByRoute("evaluation"));
    }

    private EvaluationView buildView(UUID clinicId, UUID employeeId, YearMonth month) {
        try {
            MonthlyEvaluation ev = evaluationService.evaluate(clinicId, employeeId, month);
            if (ev == null) {
                return null;
            }
            List<ComponentView> components = ev.components().stream()
                    .map(c -> new ComponentView(c.category().code(), c.category().arabicName(),
                            c.rawScore(), c.weight(), c.included(), c.overrideFloor()))
                    .toList();
            return new EvaluationView(ev.finalScore(), ev.coverage(), ev.tierName(),
                    ev.incentiveAmount(), ev.basePay(), ev.totalPay(), ev.daysLogged(), ev.frozen(), components);
        } catch (EvaluationConflictException e) {
            return null;
        }
    }

    private static YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(raw.trim());
        } catch (DateTimeParseException e) {
            return YearMonth.now();
        }
    }

    private static UUID parseEmployee(String raw, List<Employee> employees) {
        if (raw != null && !raw.isBlank()) {
            try {
                return UUID.fromString(raw.trim());
            } catch (IllegalArgumentException ignored) {
                // fall through to default
            }
        }
        return employees.isEmpty() ? null : employees.get(0).id();
    }

    record EvaluationView(BigDecimal finalScore, BigDecimal coverage, String tierName,
            BigDecimal incentiveAmount, BigDecimal basePay, BigDecimal totalPay,
            int daysLogged, boolean frozen, List<ComponentView> components) {
    }

    record ComponentView(String code, String name, BigDecimal rawScore, BigDecimal weight,
            boolean included, BigDecimal overrideFloor) {
    }

    record CompletionView(UUID dailyRecordId, UUID taskDefinitionId, String taskName,
            String workDate, String status, String statusLabel, String reason, UUID photoId,
            boolean requiresPhoto) {
    }

    record AssignmentView(UUID id, String name, String dueDate, String status, String statusLabel,
            OffsetDateTime doneAt, UUID proofPhotoId) {
    }

    record CategoryOption(String code, String name) {
    }
}