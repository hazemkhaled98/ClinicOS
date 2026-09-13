package com.clinicos.ui;

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

import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.TaskDefinitionService;
import com.clinicos.staff.api.TaskDefinitionService.TaskDefinitionRequest;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@Controller
public class TaskDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(TaskDefinitionController.class);

    private final LayoutModel layoutModel;
    private final TaskDefinitionService taskDefinitionService;
    private final EmployeeService employeeService;
    private final ActivityLogService activityLogService;

    public TaskDefinitionController(LayoutModel layoutModel, TaskDefinitionService taskDefinitionService,
            EmployeeService employeeService, ActivityLogService activityLogService) {
        this.layoutModel = layoutModel;
        this.taskDefinitionService = taskDefinitionService;
        this.employeeService = employeeService;
        this.activityLogService = activityLogService;
    }

    @GetMapping("/admin-dashboard/tasks")
    public String tasks(HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        model.addAttribute("layout", layoutModel.forRequest(session, "admin-dashboard"));
        renderCard(model, clinicId);
        return "admin/tasks-page";
    }

    @PostMapping("/admin-dashboard/tasks")
    public String createTask(@Valid TaskForm form, BindingResult binding, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.putAll(FormErrors.of(binding));
        if (fieldErrors.isEmpty()) {
            try {
                taskDefinitionService.create(clinicId, new TaskDefinitionRequest(
                        form.getName().trim(), form.getDimension(), form.getFrequency(), targetRole(form.getTarget()),
                        targetEmployee(form.getTarget()), form.isRequiresPhoto(), form.getEveryN(),
                        norm(form.getIntervalUnit())));
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "task.create", "task_definition");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("task", e.getMessage());
            }
        }
        renderCard(model, clinicId);
        Toasts.fromErrors(model, fieldErrors, "تمت إضافة المهمة");
        return "admin/tasks :: tasksCard";
    }

    @PostMapping("/admin-dashboard/tasks/{taskId}")
    public String updateTask(@PathVariable UUID taskId, @Valid TaskForm form,
            BindingResult binding, HttpSession session, Model model) {
        if (!AdminAccess.canDashboard(layoutModel, session)) {
            return "redirect:/";
        }
        UUID clinicId = AdminAccess.clinicId(session);
        Map<String, String> fieldErrors = new HashMap<>();
        fieldErrors.putAll(FormErrors.of(binding));
        if (fieldErrors.isEmpty()) {
            try {
                taskDefinitionService.update(clinicId, taskId, new TaskDefinitionRequest(
                        form.getName().trim(), form.getDimension(), form.getFrequency(), targetRole(form.getTarget()),
                        targetEmployee(form.getTarget()), form.isRequiresPhoto(), form.getEveryN(),
                        norm(form.getIntervalUnit())));
                activityLogService.log(clinicId, AdminAccess.membershipId(session), "task.update", "task_definition");
            } catch (IllegalArgumentException e) {
                fieldErrors.put("task", e.getMessage());
            }
        }
        renderCard(model, clinicId);
        Toasts.fromErrors(model, fieldErrors, "تم حفظ المهمة");
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
        renderCard(model, clinicId);
        Toasts.fromErrors(model, fieldErrors, "تم حذف المهمة");
        return "admin/tasks :: tasksCard";
    }

    private void renderCard(Model model, UUID clinicId) {
        model.addAttribute("tasks", taskDefinitionService.list(clinicId));
        model.addAttribute("employees", employeeService.list(clinicId));
    }

    private static String targetRole(String target) {
        if (target == null || !target.startsWith("role:")) {
            return null;
        }
        return target.substring("role:".length());
    }

    private static UUID targetEmployee(String target) {
        if (target == null || !target.startsWith("employee:")) {
            return null;
        }
        try {
            return UUID.fromString(target.substring("employee:".length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("الموظف غير معروف");
        }
    }

    private static String norm(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static class TaskForm {
        @NotBlank(message = "اسم المهمة مطلوب")
        private String name;
        @NotBlank(message = "البُعد مطلوب")
        private String dimension;
        @NotBlank(message = "التكرار مطلوب")
        private String frequency;
        @NotBlank(message = "الدور أو الموظف مطلوب")
        private String target;
        private boolean requiresPhoto;
        private Integer everyN;
        private String intervalUnit;

        static TaskForm of(String name, String dimension, String frequency, String target,
                boolean requiresPhoto, Integer everyN, String intervalUnit) {
            TaskForm form = new TaskForm();
            form.name = name;
            form.dimension = dimension;
            form.frequency = frequency;
            form.target = target;
            form.requiresPhoto = requiresPhoto;
            form.everyN = everyN;
            form.intervalUnit = intervalUnit;
            return form;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDimension() {
            return dimension;
        }

        public void setDimension(String dimension) {
            this.dimension = dimension;
        }

        public String getFrequency() {
            return frequency;
        }

        public void setFrequency(String frequency) {
            this.frequency = frequency;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }

        public boolean isRequiresPhoto() {
            return requiresPhoto;
        }

        public void setRequiresPhoto(boolean requiresPhoto) {
            this.requiresPhoto = requiresPhoto;
        }

        public Integer getEveryN() {
            return everyN;
        }

        public void setEveryN(Integer everyN) {
            this.everyN = everyN;
        }

        public String getIntervalUnit() {
            return intervalUnit;
        }

        public void setIntervalUnit(String intervalUnit) {
            this.intervalUnit = intervalUnit;
        }
    }
}