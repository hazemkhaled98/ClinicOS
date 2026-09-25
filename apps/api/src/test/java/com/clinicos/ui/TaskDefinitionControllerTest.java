package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.ui.nav.NavSectionResolver;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.TaskDefinitionService;
import com.clinicos.staff.api.TaskDefinitionService.TaskDefinition;
import com.clinicos.staff.api.TaskDefinitionService.TaskDefinitionRequest;

import jakarta.servlet.http.HttpSession;

class TaskDefinitionControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private LayoutModel layoutModel;
    private TaskDefinitionService taskDefinitionService;
    private EmployeeService employeeService;
    private ActivityLogService activityLogService;
    private TaskDefinitionController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        taskDefinitionService = mock(TaskDefinitionService.class);
        employeeService = mock(EmployeeService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new TaskDefinitionController(layoutModel, taskDefinitionService, employeeService, activityLogService);
        model = new ExtendedModelMap();
    }

    @Test
    void tasksRendersPageForAdmin() {
        HttpSession session = session();
        allowDashboard();
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of());

        String view = controller.tasks(session, model);

        assertThat(view).isEqualTo("admin/tasks-page");
        assertThat(model.getAttribute("tasks")).isEqualTo(List.of());
    }

    @Test
    void tasksRedirectsHomeWithoutPermission() {
        HttpSession session = session();
        denyDashboard();

        String view = controller.tasks(session, model);

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void createTaskLogsActivityAndReturnsCard() {
        HttpSession session = session();
        allowDashboard();
        TaskDefinition created = new TaskDefinition(UUID.randomUUID(), "تنظيف", "fanni", "daily", "assistant", null, false, null, null);
        when(taskDefinitionService.create(eq(CLINIC), any(TaskDefinitionRequest.class))).thenReturn(created);
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of(created));

        String view = controller.createTask(taskForm("تنظيف", "fanni", "daily", "role:assistant"), Validated.of(taskForm("تنظيف", "fanni", "daily", "role:assistant")), session, model);

        assertThat(view).isEqualTo("admin/tasks :: tasksCard");
        ArgumentCaptor<TaskDefinitionRequest> captor = ArgumentCaptor.forClass(TaskDefinitionRequest.class);
        verify(taskDefinitionService).create(eq(CLINIC), captor.capture());
        assertThat(captor.getValue().roleCode()).isEqualTo("assistant");
        assertThat(captor.getValue().employeeId()).isNull();
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "task.create", "task_definition");
    }

    @Test
    void createTaskParsesEmployeeTarget() {
        HttpSession session = session();
        allowDashboard();
        UUID empId = UUID.randomUUID();
        TaskDefinition created = new TaskDefinition(UUID.randomUUID(), "تنظيف", "fanni", "daily", null, empId, false, null, null);
        when(taskDefinitionService.create(eq(CLINIC), any(TaskDefinitionRequest.class))).thenReturn(created);
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of(created));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createTask(taskForm("تنظيف", "fanni", "daily", "employee:" + empId), Validated.of(taskForm("تنظيف", "fanni", "daily", "employee:" + empId)), session, model);

        assertThat(view).isEqualTo("admin/tasks :: tasksCard");
        ArgumentCaptor<TaskDefinitionRequest> captor = ArgumentCaptor.forClass(TaskDefinitionRequest.class);
        verify(taskDefinitionService).create(eq(CLINIC), captor.capture());
        assertThat(captor.getValue().roleCode()).isNull();
        assertThat(captor.getValue().employeeId()).isEqualTo(empId);
    }

    @Test
    void createTaskInvalidEmployeeTargetReturnsToast() {
        HttpSession session = session();
        allowDashboard();
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createTask(taskForm("تنظيف", "fanni", "daily", "employee:not-a-uuid"), Validated.of(taskForm("تنظيف", "fanni", "daily", "employee:not-a-uuid")), session, model);

        assertThat(view).isEqualTo("admin/tasks :: tasksCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).isEqualTo("الموظف غير معروف");
        verify(taskDefinitionService, never()).create(any(), any());
    }

    @Test
    void createTaskValidatesEmptyName() {
        HttpSession session = session();
        allowDashboard();
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createTask(taskForm("", "fanni", "daily", "role:assistant"), Validated.of(taskForm("", "fanni", "daily", "role:assistant")), session, model);

        assertThat(view).isEqualTo("admin/tasks :: tasksCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(taskDefinitionService, never()).create(any(), any());
    }

    @Test
    void deleteTaskLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        UUID taskId = UUID.randomUUID();
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of());

        controller.deleteTask(taskId, session, model);

        verify(taskDefinitionService).delete(CLINIC, taskId);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "task.delete", "task_definition");
    }

    @Test
    void updateTaskLogsActivityAndReturnsCard() {
        HttpSession session = session();
        allowDashboard();
        UUID taskId = UUID.randomUUID();
        TaskDefinition updated = new TaskDefinition(taskId, "تنظيف", "fanni", "daily", "assistant", null, false, null, null);
        when(taskDefinitionService.update(eq(CLINIC), eq(taskId), any(TaskDefinitionRequest.class))).thenReturn(updated);
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateTask(taskId, taskForm("تنظيف", "fanni", "daily", "role:assistant"), Validated.of(taskForm("تنظيف", "fanni", "daily", "role:assistant")), session, model);

        assertThat(view).isEqualTo("admin/tasks :: tasksCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "task.update", "task_definition");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void updateTaskValidatesEmptyName() {
        HttpSession session = session();
        allowDashboard();
        UUID taskId = UUID.randomUUID();
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of());

        String view = controller.updateTask(taskId, taskForm("", "fanni", "daily", "role:assistant"), Validated.of(taskForm("", "fanni", "daily", "role:assistant")), session, model);

        assertThat(view).isEqualTo("admin/tasks :: tasksCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("اسم المهمة مطلوب");
        verify(taskDefinitionService, never()).update(any(), any(), any());
    }

    @Test
    void updateTaskServiceErrorReturnsCard() {
        HttpSession session = session();
        allowDashboard();
        UUID taskId = UUID.randomUUID();
        when(taskDefinitionService.update(eq(CLINIC), eq(taskId), any(TaskDefinitionRequest.class)))
                .thenThrow(new IllegalArgumentException("المهمة غير موجودة"));
        when(taskDefinitionService.list(CLINIC)).thenReturn(List.of());

        String view = controller.updateTask(taskId, taskForm("تنظيف", "fanni", "daily", "role:assistant"), Validated.of(taskForm("تنظيف", "fanni", "daily", "role:assistant")), session, model);

        assertThat(view).isEqualTo("admin/tasks :: tasksCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).isEqualTo("المهمة غير موجودة");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    private static TaskDefinitionController.TaskForm taskForm(String name, String dimension,
            String frequency, String target) {
        return TaskDefinitionController.TaskForm.of(name, dimension, frequency, target, false, null, null);
    }

    private void allowDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(
                        List.of(NavSectionResolver.sectionByRoute("admin-dashboard")),
                        "أحمد", "عيادتي", "المالك", "19 مايو 2026", "admin-dashboard"));
    }

    private void denyDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "عيادتي", "مدير", "19 مايو 2026", "admin-dashboard"));
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }
}