package com.clinicos.ui;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import org.springframework.web.multipart.MultipartFile;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.ClinicSettingsService.ClinicSettings;
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.shared.AttachmentService;
import com.clinicos.staff.api.AttendanceStatus;
import com.clinicos.staff.api.AttendanceStatus.AttendanceLabel;
import com.clinicos.staff.api.DailyWorkService;
import com.clinicos.staff.api.DailyWorkService.DailyTask;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.SelfCheckService;
import com.clinicos.staff.api.SelfCheckService.DayAttendance;
import com.clinicos.staff.api.TaskAssignmentService;
import com.clinicos.staff.api.TaskAssignmentService.Assignment;
import com.clinicos.staff.api.TaskAssignmentService.AssignmentForm;
import com.clinicos.staff.api.TaskAssignmentService.Proposer;
import com.clinicos.staff.api.TaskFrequency;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@Controller
public class EmployeeDayController {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String GRID = "employees :: dayGrid";

    private final LayoutModel layoutModel;
    private final EmployeeService employeeService;
    private final SelfCheckService selfCheckService;
    private final DailyWorkService dailyWorkService;
    private final TaskAssignmentService assignmentService;
    private final ClinicSettingsService clinicSettingsService;
    private final WorkCalendarService workCalendarService;
    private final AttachmentService attachmentService;
    private final ActivityLogService activityLogService;

    public EmployeeDayController(LayoutModel layoutModel, EmployeeService employeeService,
            SelfCheckService selfCheckService, DailyWorkService dailyWorkService,
            TaskAssignmentService assignmentService, ClinicSettingsService clinicSettingsService,
            WorkCalendarService workCalendarService, AttachmentService attachmentService,
            ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.employeeService = employeeService;
        this.selfCheckService = selfCheckService;
        this.dailyWorkService = dailyWorkService;
        this.assignmentService = assignmentService;
        this.clinicSettingsService = clinicSettingsService;
        this.workCalendarService = workCalendarService;
        this.attachmentService = attachmentService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/employees")
    public String employees(HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        renderGrid(model, session);
        return "employees-page";
    }

    @PostMapping("/employees/check-in")
    public String checkIn(HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            selfCheckService.checkIn(AdminAccess.clinicId(session), employeeId(session).id());
            activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session),
                    "selfcheck.checkin", "self_check");
        } catch (IllegalArgumentException e) {
            fieldErrors.put("selfcheck", e.getMessage());
        }
        renderGrid(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم تسجيل الحضور ✔");
        return GRID;
    }

    @PostMapping("/employees/check-out")
    public String checkOut(HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            selfCheckService.checkOut(AdminAccess.clinicId(session), employeeId(session).id());
            activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session),
                    "selfcheck.checkout", "self_check");
        } catch (IllegalArgumentException e) {
            fieldErrors.put("selfcheck", e.getMessage());
        }
        renderGrid(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم تسجيل الانصراف ✔");
        return GRID;
    }

    @PostMapping("/employees/tasks/{taskId}/complete")
    public String completeTask(@PathVariable UUID taskId, @RequestParam(name = "photo", required = false) MultipartFile photo,
            HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        UUID photoId = null;
        try {
            Employee employee = employeeId(session);
            photoId = photo == null || photo.isEmpty() ? null
                    : attachmentService.upload(clinicId,
                            AdminAccess.membershipId(session), photo).id();
            dailyWorkService.complete(clinicId, employee.id(), taskId, photoId);
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "task.complete", "daily_task_completion");
        } catch (IllegalArgumentException e) {
            if (photoId != null) {
                attachmentService.delete(clinicId, photoId);
            }
            fieldErrors.put("task", e.getMessage());
        }
        renderGrid(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم تأكيد المهمة ✔");
        return GRID;
    }

    @PostMapping("/employees/tasks/{taskId}/uncomplete")
    public String uncompleteTask(@PathVariable UUID taskId, HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            dailyWorkService.uncomplete(AdminAccess.clinicId(session), employeeId(session).id(), taskId);
            activityLogService.log(AdminAccess.clinicId(session), AdminAccess.membershipId(session),
                    "task.uncomplete", "daily_task_completion");
        } catch (IllegalArgumentException e) {
            fieldErrors.put("task", e.getMessage());
        }
        renderGrid(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم إلغاء تأكيد المهمة");
        return GRID;
    }

    @PostMapping("/employees/assignments/{assignmentId}/done")
    public String assignmentDone(@PathVariable UUID assignmentId,
            @RequestParam(name = "photo", required = false) MultipartFile photo,
            HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        UUID clinicId = AdminAccess.clinicId(session);
        UUID photoId = null;
        try {
            Employee employee = employeeId(session);
            photoId = photo == null || photo.isEmpty() ? null
                    : attachmentService.upload(clinicId,
                            AdminAccess.membershipId(session), photo).id();
            assignmentService.markDone(clinicId, employee.id(), assignmentId, photoId);
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "assignment.done", "task_assignment");
        } catch (IllegalArgumentException e) {
            if (photoId != null) {
                attachmentService.delete(clinicId, photoId);
            }
            fieldErrors.put("assignment", e.getMessage());
        }
        renderGrid(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم تسليم المهمة ✔");
        return GRID;
    }

    @PostMapping("/employees/assignments/propose")
    public String proposeAssignment(@Valid AssignmentFormDto form, HttpSession session, Model model) {
        if (!canView(session)) {
            return "redirect:/";
        }
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            Employee employee = employeeId(session);
            LocalDate due = form.getDueDate() == null || form.getDueDate().isBlank()
                    ? LocalDate.now()
                    : LocalDate.parse(form.getDueDate().trim(), DATE);
            UUID clinicId = AdminAccess.clinicId(session);
            assignmentService.propose(clinicId, employee.id(),
                    new AssignmentForm(employee.id(), form.getName().trim(), due), Proposer.SELF);
            activityLogService.log(clinicId, AdminAccess.membershipId(session),
                    "assignment.propose", "task_assignment");
        } catch (IllegalArgumentException e) {
            fieldErrors.put("assignment", e.getMessage());
        }
        renderGrid(model, session);
        Toasts.fromErrors(model, fieldErrors, "تم إرسال الاقتراح للمدير ✔");
        return GRID;
    }

    private boolean canView(HttpSession session) {
        return layoutModel.forRequest(session, "employees")
                .nav()
                .contains(NavSectionResolver.sectionByRoute("employees"));
    }

    private Employee employeeId(HttpSession session) {
        Employee employee = employeeService.findByMembership(
                AdminAccess.clinicId(session), AdminAccess.membershipId(session));
        if (employee == null) {
            throw new IllegalArgumentException("حسابك غير مرتبط بملف موظف");
        }
        return employee;
    }

    private void renderGrid(Model model, HttpSession session) {
        model.addAttribute("layout", layoutModel.forRequest(session, "employees"));
        UUID clinicId = AdminAccess.clinicId(session);
        Employee employee = employeeService.findByMembership(clinicId, AdminAccess.membershipId(session));
        DayView day;
        if (employee == null) {
            day = new DayView(true, null, null, null, null, false, false, false, null, null, 0, false);
        } else {
            day = buildDay(clinicId, employee);
        }
        model.addAttribute("day", day);
    }

    private DayView buildDay(UUID clinicId, Employee employee) {
        LocalDate today = LocalDate.now();
        DayAttendance attendance = selfCheckService.today(clinicId, employee.id());
        ClinicSettings settings = clinicSettingsService.get(clinicId);
        LocalTime shiftStart = employee.customShift() ? employee.shiftStart() : settings.defaultShiftStart();
        LocalTime shiftEnd = employee.customShift() ? employee.shiftEnd() : settings.defaultShiftEnd();
        int grace = settings.lateGraceMinutes();
        boolean dayOff = !workCalendarService.isWorkday(clinicId, today, employee.id());
        boolean shiftOver = LocalTime.now().compareTo(shiftEnd) >= 0;
        boolean locked = attendance.checkedInAt() == null;

        AttendanceView att = new AttendanceView(
                attendance.checkedInAt() != null,
                attendance.checkedOutAt() != null,
                attendance.checkedInAt() == null ? null : TIME.format(attendance.checkedInAt()),
                attendance.checkedOutAt() == null ? null : TIME.format(attendance.checkedOutAt()),
                attendance.checkedInAt() == null ? null
                        : AttendanceStatus.checkInLabel(
                                attendance.checkedInAt().toLocalTime(), shiftStart, shiftEnd, grace),
                attendance.checkedOutAt() == null ? null
                        : AttendanceStatus.checkOutLabel(
                                attendance.checkedOutAt().toLocalTime(), shiftEnd, grace));

        List<DailyTask> tasks = dailyWorkService.today(clinicId, employee.id());
        List<DailyTaskView> daily = new ArrayList<>();
        List<DailyTaskView> periodic = new ArrayList<>();
        for (DailyTask task : tasks) {
            if ("daily".equals(task.frequency())) {
                daily.add(toTaskView(task, today, false));
            } else {
                periodic.add(toTaskView(task, today, true));
            }
        }

        List<TaskGroup> groups = new ArrayList<>();
        for (String dimension : List.of("fanni", "solooki", "ibda3")) {
            List<DailyTaskView> rows = daily.stream()
                    .filter(t -> dimension.equals(t.dimension()))
                    .toList();
            if (!rows.isEmpty()) {
                groups.add(new TaskGroup(dimension, dimensionLabel(dimension), rows));
            }
        }

        List<AssignmentView> assignments = assignmentService.today(clinicId, employee.id())
                .stream()
                .map(a -> toAssignmentView(a, locked))
                .toList();

        return new DayView(false, att,
                groups.isEmpty() ? List.of() : groups,
                periodic,
                assignments,
                locked, dayOff, shiftOver,
                TIME.format(shiftStart), TIME.format(shiftEnd), grace,
                employee.customShift());
    }

    private DailyTaskView toTaskView(DailyTask task, LocalDate today, boolean periodic) {
        String frequencyLabel;
        if ("custom".equals(task.frequency())) {
            int every = task.everyN() == null ? 1 : task.everyN();
            frequencyLabel = every > 1 ? "كل " + every + " " + unitLabel(task.intervalUnit())
                    : "كل " + unitLabel(task.intervalUnit());
        } else {
            frequencyLabel = switch (task.frequency()) {
                case "daily" -> "يومي";
                case "weekly" -> "أسبوعي";
                case "monthly" -> "شهري";
                default -> task.frequency();
            };
        }
        String dueLabel = null;
        String dueState = null;
        if (periodic) {
            int periodDays = TaskFrequency.periodDays(task.frequency(),
                    task.everyN() == null ? 1 : task.everyN(), task.intervalUnit());
            TaskFrequency.DueStatus due = TaskFrequency.dueStatus(today, task.lastCompletedDate(), periodDays);
            dueLabel = due.label();
            dueState = due.state();
        }
        return new DailyTaskView(
                task.taskDefinitionId(),
                task.name(),
                task.dimension(),
                task.frequency(),
                frequencyLabel,
                task.requiresPhoto(),
                task.done(),
                task.completedAt() == null ? null : "✔ أكّدتها " + TIME.format(task.completedAt()),
                task.photoId(),
                dueLabel,
                dueState);
    }

    private AssignmentView toAssignmentView(Assignment a, boolean locked) {
        String statusLabel;
        String statusCss;
        boolean done = a.doneAt() != null;
        if (done) {
            statusLabel = "سلّمتها — تنتظر اعتماد المدير";
            statusCss = "clinicos-chip--warning";
        } else {
            switch (a.status()) {
                case "pending" -> {
                    statusLabel = "في انتظار موافقة المدير";
                    statusCss = "clinicos-chip--info";
                }
                case "rejected" -> {
                    statusLabel = "رُفضت — راجعها";
                    statusCss = "clinicos-chip--danger";
                }
                default -> {
                    statusLabel = "مطلوبة منك";
                    statusCss = "clinicos-chip--info";
                }
            }
        }
        boolean canMarkDone = !locked && !done && "approved".equals(a.status());
        return new AssignmentView(
                a.id(),
                a.name(),
                statusLabel,
                statusCss,
                a.dueDate() == null ? null : "📅 تسليمها: " + DATE.format(a.dueDate()),
                done,
                canMarkDone,
                a.proofPhotoId());
    }

    private static String dimensionLabel(String dimension) {
        return switch (dimension) {
            case "fanni" -> "فني";
            case "solooki" -> "سلوكي";
            case "ibda3" -> "إبداعي";
            default -> "مهام عامة";
        };
    }

    private static String unitLabel(String intervalUnit) {
        return switch (intervalUnit == null ? "week" : intervalUnit) {
            case "day" -> "يوم";
            case "month" -> "شهر";
            default -> "أسبوع";
        };
    }

    record DayView(
            boolean noEmployee,
            AttendanceView attendance,
            List<TaskGroup> dailyGroups,
            List<DailyTaskView> periodicTasks,
            List<AssignmentView> assignments,
            boolean locked,
            boolean dayOff,
            boolean shiftOver,
            String shiftStart,
            String shiftEnd,
            int graceMinutes,
            boolean customShift) {
    }

    record AttendanceView(
            boolean checkedIn,
            boolean checkedOut,
            String inTime,
            String outTime,
            AttendanceLabel inLabel,
            AttendanceLabel outLabel) {
    }

    record TaskGroup(String dimension, String label, List<DailyTaskView> tasks) {
    }

    record DailyTaskView(
            UUID taskDefinitionId,
            String name,
            String dimension,
            String frequency,
            String frequencyLabel,
            boolean requiresPhoto,
            boolean done,
            String doneLabel,
            UUID photoId,
            String dueLabel,
            String dueState) {
    }

    record AssignmentView(
            UUID id,
            String name,
            String statusLabel,
            String statusCss,
            String dueLabel,
            boolean done,
            boolean canMarkDone,
            UUID proofPhotoId) {
    }

    public static class AssignmentFormDto {
        @NotBlank(message = "اسم المهمة مطلوب")
        private String name;
        private String dueDate;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDueDate() {
            return dueDate;
        }

        public void setDueDate(String dueDate) {
            this.dueDate = dueDate;
        }
    }
}