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
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(EvaluationController.class);

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
        return handleAction(session, model, employeeId, month,
                "eval.approve", "daily_task_completion", "تم اعتماد الإنجاز ✔",
                clinicId -> dailyWorkService.approveReview(clinicId, dailyRecordId, taskId,
                        AdminAccess.membershipId(session)));
    }

    @PostMapping("/evaluation/{employeeId}/completion/{dailyRecordId}/{taskId}/reject")
    public String rejectCompletion(@PathVariable UUID employeeId, @PathVariable UUID dailyRecordId,
            @PathVariable UUID taskId, @RequestParam String month, @RequestParam(required = false) String reason,
            HttpSession session, Model model) {
        return handleAction(session, model, employeeId, month,
                "eval.reject", "daily_task_completion", "تم رفض الإنجاز",
                clinicId -> dailyWorkService.rejectReview(clinicId, dailyRecordId, taskId,
                        AdminAccess.membershipId(session), reason));
    }

    @PostMapping("/evaluation/{employeeId}/assignment/{assignmentId}/approve")
    public String approveAssignment(@PathVariable UUID employeeId, @PathVariable UUID assignmentId,
            @RequestParam String month, HttpSession session, Model model) {
        return handleAction(session, model, employeeId, month,
                "eval.approve", "task_assignment", "تم اعتماد المهمة ✔",
                clinicId -> assignmentService.approve(clinicId, assignmentId, AdminAccess.membershipId(session)));
    }

    @PostMapping("/evaluation/{employeeId}/assignment/{assignmentId}/reject")
    public String rejectAssignment(@PathVariable UUID employeeId, @PathVariable UUID assignmentId,
            @RequestParam String month, @RequestParam(required = false) String reason,
            HttpSession session, Model model) {
        return handleAction(session, model, employeeId, month,
                "eval.reject", "task_assignment", "تم رفض المهمة",
                clinicId -> assignmentService.reject(clinicId, assignmentId,
                        AdminAccess.membershipId(session), reason));
    }

    @PostMapping("/evaluation/{employeeId}/assign")
    public String assign(@PathVariable UUID employeeId, @RequestParam String month,
            @RequestParam String name, @RequestParam(required = false) String dueDate,
            HttpSession session, Model model) {
        if (name == null || name.isBlank()) {
            if (!canView(session)) {
                return "redirect:/";
            }
            UUID clinicId = AdminAccess.clinicId(session);
            renderGrid(model, clinicId, employeeId, parseMonth(month));
            Toasts.fromErrors(model, Map.of("assignment", "اسم المهمة مطلوب"), "تم تعيين المهمة ✔");
            return GRID;
        }
        return handleAction(session, model, employeeId, month,
                "eval.assign", "task_assignment", "تم تعيين المهمة ✔",
                clinicId -> {
                    LocalDate due = dueDate == null || dueDate.isBlank() ? null : LocalDate.parse(dueDate.trim());
                    assignmentService.propose(clinicId, employeeId,
                            new AssignmentForm(employeeId, name.strip(), due), Proposer.MANAGER);
                });
    }

    @PostMapping("/evaluation/{employeeId}/override")
    public String override(@PathVariable UUID employeeId, @RequestParam String month,
            @RequestParam String category, @RequestParam String floorValue,
            HttpSession session, Model model) {
        return handleAction(session, model, employeeId, month,
                "eval.override", "performance_override", "تم حفظ الحد الأدنى ✔",
                clinicId -> {
                    Category cat = Category.fromCode(category.trim());
                    if (cat == null) {
                        throw new IllegalArgumentException("فئة غير معروفة");
                    }
                    BigDecimal floor = new BigDecimal(floorValue.trim());
                    evaluationService.setOverride(clinicId, employeeId, parseMonth(month), cat, floor,
                            AdminAccess.membershipId(session));
                });
    }

    @PostMapping("/evaluation/{employeeId}/unlock")
    public String unlock(@PathVariable UUID employeeId, @RequestParam String month,
            HttpSession session, Model model) {
        return handleAction(session, model, employeeId, month,
                "eval.unlock", "evaluation_snapshot", "تم فتح الشهر لإعادة التقييم ✔",
                clinicId -> evaluationService.unlock(clinicId, employeeId, parseMonth(month),
                        AdminAccess.membershipId(session)));
    }

    private String handleAction(HttpSession session, Model model, UUID employeeId, String month,
            String logAction, String logEntity, String successMessage,
            Consumer<UUID> action) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> errors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        try {
            action.accept(clinicId);
            activityLogService.log(clinicId, AdminAccess.membershipId(session), logAction, logEntity);
        } catch (IllegalArgumentException | EvaluationConflictException e) {
            log.warn("evaluation action failed: clinicId={}, logAction={}", clinicId, logAction, e);
            errors.put("error", e.getMessage());
        }
        renderGrid(model, clinicId, employeeId, parseMonth(month));
        Toasts.fromErrors(model, errors, successMessage);
        return GRID;
    }

    private void renderGrid(Model model, UUID clinicId, UUID employeeId, YearMonth month) {
        model.addAttribute("month", month.toString());
        model.addAttribute("selectedId", employeeId);
        model.addAttribute("isClosed", month.isBefore(YearMonth.now()));
        EvaluationView view;
        try {
            view = buildView(clinicId, employeeId, month);
        } catch (EvaluationConflictException e) {
            view = null;
            model.addAttribute("conflictMessage", e.getMessage());
        }
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