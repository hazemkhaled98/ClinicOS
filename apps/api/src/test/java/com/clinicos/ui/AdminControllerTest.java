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

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserSummary;
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
    private ClinicSettingsService clinicSettingsService;
    private UserAdminService userAdminService;
    private AdminController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        employeeService = mock(EmployeeService.class);
        activityLogService = mock(ActivityLogService.class);
        clinicSettingsService = mock(ClinicSettingsService.class);
        userAdminService = mock(UserAdminService.class);
        controller = new AdminController(layoutModel, employeeService, activityLogService, clinicSettingsService, userAdminService);
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
        var settings = new ClinicSettingsService.ClinicSettings(
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0), 15, 26,
                new java.math.BigDecimal("20000"), 70, List.of(), List.of());
        when(clinicSettingsService.get(CLINIC)).thenReturn(settings);

        String view = controller.settings(session, model);

        assertThat(view).isEqualTo("admin/settings");
        assertThat(model.getAttribute("layout")).isNotNull();
        assertThat(model.getAttribute("employees")).isEqualTo(List.of());
        assertThat(model.getAttribute("settings")).isEqualTo(settings);
        assertThat(model.getAttribute("weights")).isEqualTo(List.of());
        assertThat(model.getAttribute("tiers")).isEqualTo(List.of());
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
    void updateEmployeeLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        Employee updated = employee("محمود", StaffRole.ASSISTANT);
        when(employeeService.update(eq(CLINIC), eq(updated.id()), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateEmployee(updated.id(), new AdminController.EmployeeForm(
                "محمود", "assistant", null, "5200", "2000", false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "employee.update", "employee");
        assertThat(model.getAttribute("employeeErrorScope")).isNull();
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ بيانات الموظف");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void updateEmployeeSurvivesConcurrentArchive() {
        HttpSession session = session();
        allowDashboard();
        when(employeeService.update(eq(CLINIC), any(UUID.class), any(EmployeeRequest.class)))
                .thenThrow(new IllegalArgumentException("الموظف غير موجود"));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.updateEmployee(UUID.randomUUID(), new AdminController.EmployeeForm(
                "محمود", "assistant", null, "5200", "2000", false, "", ""), session, model);

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
    void archiveEmployeeServiceErrorReturnsCard() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        when(employeeService.archive(CLINIC, employeeId))
                .thenThrow(new IllegalArgumentException("الموظف غير موجود"));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.archiveEmployee(employeeId, session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("employeeErrorScope")).isEqualTo("archive");
        assertThat(((Map<?, ?>) model.getAttribute("employeeErrors")).get("employee"))
                .isEqualTo("الموظف غير موجود");
        verify(activityLogService, never()).log(any(), any(), any(), any());
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
    }

    @Test
    void updateEmployeeWithChangedRoleAssignsRole() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UserSummary summary = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", null, "active", "assistant", membershipId, employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(summary));
        Employee updated = new Employee(employeeId, "محمود", StaffRole.ASSISTANT, null, null, null, null, false, null, null);
        when(employeeService.update(eq(CLINIC), eq(employeeId), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateEmployee(employeeId, new AdminController.EmployeeForm(
                "محمود", "assistant", "manager", "5200", "2000", false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(userAdminService).assignRole(CLINIC, membershipId, "manager");
        assertThat(model.getAttribute("toastMessage")).isEqualTo("تم حفظ بيانات الموظف");
    }

    @Test
    void updateEmployeeOwnerRowNeverAssignsRole() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UserSummary summary = new UserSummary(UUID.randomUUID(), "owner", "المالك", null, "active", "owner", membershipId, employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(summary));
        Employee updated = new Employee(employeeId, "المالك", StaffRole.ASSISTANT, null, null, null, null, false, null, null);
        when(employeeService.update(eq(CLINIC), eq(employeeId), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateEmployee(employeeId, new AdminController.EmployeeForm(
                "المالك", "assistant", "manager", "5200", "2000", false, "", ""), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(userAdminService, never()).assignRole(any(), any(), any());
    }

    @Test
    void updateEmployeeWithoutRoleSelectSkipsAssignRole() {
        HttpSession session = session();
        allowDashboard();
        Employee updated = employee("محمود", StaffRole.ASSISTANT);
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.update(eq(CLINIC), eq(updated.id()), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        controller.updateEmployee(updated.id(), new AdminController.EmployeeForm(
                "محمود", "assistant", null, "5200", "2000", false, "", ""), session, model);

        verify(userAdminService, never()).assignRole(any(), any(), any());
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
