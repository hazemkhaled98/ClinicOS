package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.identity.api.SessionKeys;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.EmployeeService.EmployeeRequest;
import com.clinicos.staff.api.EmployeeService.EmployeeValidationException;
import com.clinicos.staff.api.EmployeeService.StaffRole;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class AdminControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private LayoutModel layoutModel;
    private EmployeeService employeeService;
    private ActivityLogService activityLogService;
    private AdminController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        employeeService = mock(EmployeeService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new AdminController(layoutModel, employeeService, activityLogService);
        model = new ExtendedModelMap();
    }

    @Test
    void indexRedirectsToSettings() {
        assertThat(controller.index()).isEqualTo("redirect:/admin-dashboard/settings");
    }

    @Test
    void settingsRendersAdminPageForCeo() {
        HttpSession session = session();
        allowDashboard();
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.settings(session, model);

        assertThat(view).isEqualTo("admin/settings");
        assertThat(model.getAttribute("layout")).isNotNull();
        assertThat(model.getAttribute("employees")).isEqualTo(List.of());
        assertThat(model.getAttribute("addForm")).isEqualTo(AdminController.EmployeeForm.empty());
    }

    @Test
    void settingsRedirectsHomeWithoutCeoPermission() {
        HttpSession session = session();
        denyDashboard();
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.settings(session, model);

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void createEmployeeLogsActivityAndReturnsCard() {
        HttpSession session = session();
        allowDashboard();
        Employee created = employee("محمود", StaffRole.ASSISTANT);
        when(employeeService.create(eq(CLINIC), any(EmployeeRequest.class))).thenReturn(created);
        when(employeeService.list(CLINIC)).thenReturn(List.of(created));

        String view = controller.createEmployee(new AdminController.EmployeeForm(
                "محمود", "assistant", "5000", "1500", false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "employee.create", "employee");
        assertThat(model.getAttribute("employeeErrorScope")).isNull();
    }

    @Test
    void createEmployeeRerendersCardWithErrors() {
        HttpSession session = session();
        allowDashboard();
        when(employeeService.create(eq(CLINIC), any(EmployeeRequest.class)))
                .thenThrow(new EmployeeValidationException(Map.of("name", "اسم الموظف مطلوب")));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createEmployee(new AdminController.EmployeeForm(
                "", "assistant", "5000", "1500", false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("employeeErrorScope")).isEqualTo("add");
        assertThat(model.getAttribute("employeeErrors")).isEqualTo(Map.of("name", "اسم الموظف مطلوب"));
    }

    @Test
    void updateEmployeeLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        Employee updated = employee("محمود", StaffRole.ASSISTANT);
        when(employeeService.update(eq(CLINIC), eq(updated.id()), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateEmployee(updated.id(), new AdminController.EmployeeForm(
                "محمود", "assistant", "5200", "2000", false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "employee.update", "employee");
        assertThat(model.getAttribute("employeeErrorScope")).isNull();
    }

    @Test
    void updateEmployeeSurvivesConcurrentArchive() {
        HttpSession session = session();
        allowDashboard();
        when(employeeService.update(eq(CLINIC), any(UUID.class), any(EmployeeRequest.class)))
                .thenThrow(new IllegalArgumentException("الموظف غير موجود"));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.updateEmployee(UUID.randomUUID(), new AdminController.EmployeeForm(
                "محمود", "assistant", "5200", "2000", false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void archiveEmployeeLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        Employee archived = employee("محمود", StaffRole.ASSISTANT);
        when(employeeService.archive(CLINIC, archived.id())).thenReturn(archived);
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.archiveEmployee(archived.id(), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "employee.archive", "employee");
    }

    @Test
    void malformedAmountReportsArabicErrorAndSkipsService() {
        HttpSession session = session();
        allowDashboard();
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createEmployee(new AdminController.EmployeeForm(
                "محمود", "assistant", "abc", "1500", false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("employeeErrorScope")).isEqualTo("add");
        assertThat(model.getAttribute("employeeErrors"))
                .isEqualTo(Map.of("basePay", "المرتب الأساسي غير صحيح"));
        verify(employeeService, never()).create(any(), any());
    }

    @Test
    void unknownRoleCodeReportsArabicError() {
        HttpSession session = session();
        allowDashboard();
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createEmployee(new AdminController.EmployeeForm(
                "محمود", "doctor", null, null, false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("employeeErrors"))
                .isEqualTo(Map.of("staffRole", "المسمى الوظيفي غير معروف: doctor"));
        verify(employeeService, never()).create(any(), any());
    }

    private void allowDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(ceoLayout());
    }

    private void denyDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "مدير", "19 مايو 2026", "admin-dashboard"));
    }

    private static LayoutModel.LayoutData ceoLayout() {
        return new LayoutModel.LayoutData(
                List.of(NavSectionResolver.sectionByRoute("admin-dashboard")),
                "أحمد", "المالك", "19 مايو 2026", "admin-dashboard");
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }

    private static Employee employee(String name, StaffRole role) {
        return new Employee(UUID.randomUUID(), name, role, null, null, null, null, false, null, null);
    }
}
