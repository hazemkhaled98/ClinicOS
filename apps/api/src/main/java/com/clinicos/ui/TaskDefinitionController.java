package com.clinicos.ui;

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

import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.TaskDefinitionService;
import com.clinicos.staff.api.TaskDefinitionService.TaskDefinitionRequest;

import jakarta.servlet.http.HttpSession;

@Controller
public class TaskDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(TaskDefinitionController.class);

    private final LayoutModel layoutModel;
    private final TaskDefinitionService taskDefinitionService;
    private final ActivityLogService activityLogService;

    public TaskDefinitionController(LayoutModel layoutModel, TaskDefinitionService taskDefinitionService,
            ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.taskDefinitionService = taskDefinitionService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin-dashboard/tasks")
    public String tasks(HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        model.addAttribute("tasks", taskDefinitionService.list(clinicId));
        model.addAttribute("taskErrors", Map.of());
        model.addAttribute("taskErrorScope", (String) null);
        return "admin/tasks-page";
    }

    @PostMapping("/admin-dashboard/tasks")
    public String createTask(TaskForm form, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        validate(form, fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                taskDefinitionService.create(clinicId, new TaskDefinitionRequest(
                        form.name().trim(), form.dimension(), form.frequency(), form.roleCode()));
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "task.create", "task_definition");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("task", e.getMessage());
            }
        }
        renderCard(model, clinicId, fieldErrors, fieldErrors.isEmpty() ? null : "add");
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تمت إضافة المهمة");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/tasks :: tasksCard";
    }

    @PostMapping("/admin-dashboard/tasks/{taskId}")
    public String updateTask(@PathVariable UUID taskId, TaskForm form,
            HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        validate(form, fieldErrors);
        if (fieldErrors.isEmpty()) {
            try {
                taskDefinitionService.update(clinicId, taskId, new TaskDefinitionRequest(
                        form.name().trim(), form.dimension(), form.frequency(), form.roleCode()));
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "task.update", "task_definition");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("task", e.getMessage());
            }
        }
        renderCard(model, clinicId, fieldErrors, fieldErrors.isEmpty() ? null : "edit");
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تم حفظ المهمة");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/tasks :: tasksCard";
    }

    @DeleteMapping("/admin-dashboard/tasks/{taskId}")
    public String deleteTask(@PathVariable UUID taskId, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        try {
            taskDefinitionService.delete(clinicId, taskId);
            activityLogService.log(clinicId, AdminAccess.membershipId(session), "task.delete", "task_definition");
        } catch (IllegalArgumentException e) {
            // not found / already archived / RLS-hidden
            log.warn("deleteTask failed: task {} clinic {}", taskId, clinicId, e);
            fieldErrors.put("task", e.getMessage());
        }
        renderCard(model, clinicId, fieldErrors, fieldErrors.isEmpty() ? null : "delete");
        if (fieldErrors.isEmpty()) {
            Toasts.success(model, "تم حذف المهمة");
        } else {
            Toasts.error(model, String.join("؛ ", fieldErrors.values()));
        }
        return "admin/tasks :: tasksCard";
    }

    private void renderCard(Model model, UUID clinicId, Map<String, String> fieldErrors, String errorScope) {
        model.addAttribute("tasks", taskDefinitionService.list(clinicId));
        model.addAttribute("taskErrors", fieldErrors);
        model.addAttribute("taskErrorScope", errorScope);
    }

    private static void validate(TaskForm form, Map<String, String> errors) {
        if (form.name() == null || form.name().isBlank()) {
            errors.put("name", "اسم المهمة مطلوب");
        }
        if (form.dimension() == null || form.dimension().isBlank()) {
            errors.put("dimension", "البُعد مطلوب");
        }
        if (form.frequency() == null || form.frequency().isBlank()) {
            errors.put("frequency", "التكرار مطلوب");
        }
        if (form.roleCode() == null || form.roleCode().isBlank()) {
            errors.put("roleCode", "الدور مطلوب");
        }
    }

    public record TaskForm(String name, String dimension, String frequency, String roleCode) {
    }
}
