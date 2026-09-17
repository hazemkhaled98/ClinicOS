package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.clinicconfig.api.ClinicSettingsService.Category;
import com.clinicos.evaluation.api.EvaluationService;
import com.clinicos.evaluation.api.EvaluationService.MonthlyEvaluation;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.DailyWorkService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.TaskAssignmentService;
import com.clinicos.staff.api.TaskAssignmentService.AssignmentForm;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class EvaluationControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();
    private static final String GRID = "evaluation :: reviewGrid";

    private LayoutModel layoutModel;
    private EmployeeService employeeService;
    private EvaluationService evaluationService;
    private DailyWorkService dailyWorkService;
    private TaskAssignmentService assignmentService;
    private ActivityLogService activityLogService;
    private EvaluationController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        employeeService = mock(EmployeeService.class);
        evaluationService = mock(EvaluationService.class);
        dailyWorkService = mock(DailyWorkService.class);
        assignmentService = mock(TaskAssignmentService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new EvaluationController(layoutModel, employeeService, evaluationService,
                dailyWorkService, assignmentService, activityLogService);
        model = new ExtendedModelMap();
        when(employeeService.list(CLINIC)).thenReturn(List.of());
    }

    @Test
    void rendersPageForManager() {
        allowView();

        String view = controller.evaluation(null, null, session(), model);

        assertThat(view).isEqualTo("evaluation-page");
        assertThat((String) model.getAttribute("month")).isEqualTo(YearMonth.now().toString());
        assertThat(model.getAttribute("view")).isNull();
    }

    @Test
    void redirectsHomeWithoutPermission() {
        denyView();

        String view = controller.evaluation(null, null, session(), model);

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void approvesCompletionLogsActivity() {
        allowView();
        UUID emp = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        String view = controller.approveCompletion(emp, recordId, taskId,
                YearMonth.now().toString(), session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(dailyWorkService).approveReview(CLINIC, recordId, taskId, MEMBERSHIP);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "eval.approve", "daily_task_completion");
    }

    @Test
    void rejectsCompletionPassesReason() {
        allowView();
        UUID emp = UUID.randomUUID();
        UUID recordId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();

        String view = controller.rejectCompletion(emp, recordId, taskId,
                YearMonth.now().toString(), "غير موثقة", session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(dailyWorkService).rejectReview(CLINIC, recordId, taskId, MEMBERSHIP, "غير موثقة");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "eval.reject", "daily_task_completion");
    }

    @Test
    void approvesAssignmentLogsActivity() {
        allowView();
        UUID emp = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();

        String view = controller.approveAssignment(emp, assignmentId,
                YearMonth.now().toString(), session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(assignmentService).approve(CLINIC, assignmentId, MEMBERSHIP);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "eval.approve", "task_assignment");
    }

    @Test
    void rejectsAssignmentPassesReason() {
        allowView();
        UUID emp = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();

        String view = controller.rejectAssignment(emp, assignmentId,
                YearMonth.now().toString(), "مطلوب تعديل", session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(assignmentService).reject(CLINIC, assignmentId, MEMBERSHIP, "مطلوب تعديل");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "eval.reject", "task_assignment");
    }

    @Test
    void assignsNewTaskWithDueDate() {
        allowView();
        UUID emp = UUID.randomUUID();

        String view = controller.assign(emp, YearMonth.now().toString(),
                "تنظيف العيادة", "2026-09-20", session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        ArgumentCaptor<AssignmentForm> captor = ArgumentCaptor.forClass(AssignmentForm.class);
        verify(assignmentService).propose(eq(CLINIC), eq(emp), captor.capture(), eq(TaskAssignmentService.Proposer.MANAGER));
        assertThat(captor.getValue().name()).isEqualTo("تنظيف العيادة");
        assertThat(captor.getValue().dueDate()).isEqualTo(LocalDate.of(2026, 9, 20));
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "eval.assign", "task_assignment");
    }

    @Test
    void assignMissingNameShowsError() {
        allowView();
        UUID emp = UUID.randomUUID();

        String view = controller.assign(emp, YearMonth.now().toString(), "  ", null, session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat((String) model.getAttribute("toastMessage")).contains("اسم المهمة");
        verify(assignmentService, never()).propose(any(), any(), any(), any());
    }

    @Test
    void overrideSetsFloorAndLogs() {
        allowView();
        UUID emp = UUID.randomUUID();

        String view = controller.override(emp, YearMonth.now().toString(), "fanni", "70", session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(evaluationService).setOverride(CLINIC, emp, YearMonth.now(), Category.FANNI,
                new BigDecimal("70"), MEMBERSHIP);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "eval.override", "performance_override");
    }

    @Test
    void overrideUnknownCategoryShowsError() {
        allowView();
        UUID emp = UUID.randomUUID();

        String view = controller.override(emp, YearMonth.now().toString(), "nope", "70", session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(evaluationService, never()).setOverride(any(), any(), any(), any(), any(), any());
    }

    @Test
    void unlockFrozenMonthLogs() {
        allowView();
        UUID emp = UUID.randomUUID();

        String view = controller.unlock(emp, YearMonth.now().toString(), session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(evaluationService).unlock(CLINIC, emp, YearMonth.now(), MEMBERSHIP);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "eval.unlock", "evaluation_snapshot");
    }

    @Test
    void unlockConflictSurfacesArabicToast() {
        allowView();
        UUID emp = UUID.randomUUID();
        doThrow(new EvaluationService.EvaluationConflictException("الشهر مقفل"))
                .when(evaluationService).unlock(any(), any(), any(), any());

        String view = controller.unlock(emp, YearMonth.now().toString(), session(), model);

        assertThat(view).isEqualTo(GRID);
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat((String) model.getAttribute("toastMessage")).contains("الشهر مقفل");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void pageExposesMonthlyEvaluationForSelectedEmployee() {
        allowView();
        UUID emp = UUID.randomUUID();
        String name = "أحمد";
        when(employeeService.list(CLINIC)).thenReturn(List.of(new Employee(
                emp, name, new BigDecimal("5000"), new BigDecimal("0.1"), null, null, false, null, null)));
        MonthlyEvaluation ev = new MonthlyEvaluation(
                new BigDecimal("68.57"), new BigDecimal("0.70"), List.of(), "جيد",
                new BigDecimal("500.00"), new BigDecimal("5000.00"), new BigDecimal("5500.00"),
                30, true);
        when(evaluationService.evaluate(CLINIC, emp, YearMonth.now())).thenReturn(ev);

        String view = controller.evaluation(null, emp.toString(), session(), model);

        assertThat(view).isEqualTo("evaluation-page");
        assertThat(model.getAttribute("view")).isNotNull();
        assertThat(model.getAttribute("completions")).isNotNull();
        assertThat(model.getAttribute("assignments")).isNotNull();
    }

    private void allowView() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("evaluation")))
                .thenReturn(new LayoutModel.LayoutData(
                        List.of(NavSectionResolver.sectionByRoute("evaluation")),
                        "أحمد", "المدير", "19 مايو 2026", "evaluation"));
    }

    private void denyView() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("evaluation")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "موظف", "19 مايو 2026", "evaluation"));
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }
}