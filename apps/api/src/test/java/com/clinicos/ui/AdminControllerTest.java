package com.clinicos.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import com.clinicos.clinicconfig.api.ClinicSettingsService;
import com.clinicos.clinicconfig.api.WorkCalendarService;
import com.clinicos.identity.api.SessionKeys;
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.staff.api.EmployeeService.Employee;
import com.clinicos.staff.api.EmployeeService.EmployeeRequest;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class AdminControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private LayoutModel layoutModel;
    private EmployeeService employeeService;
    private ActivityLogService activityLogService;
    private ClinicSettingsService clinicSettingsService;
    private WorkCalendarService workCalendarService;
    private UserAdminService userAdminService;
    private AdminController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        employeeService = mock(EmployeeService.class);
        activityLogService = mock(ActivityLogService.class);
        clinicSettingsService = mock(ClinicSettingsService.class);
        workCalendarService = mock(WorkCalendarService.class);
        userAdminService = mock(UserAdminService.class);
        controller = new AdminController(layoutModel, employeeService, activityLogService,
                clinicSettingsService, workCalendarService, userAdminService);
        model = new ExtendedModelMap();
        when(workCalendarService.workingWeekdays(CLINIC)).thenReturn(List.of(6, 7, 1, 2, 3, 4));
        when(workCalendarService.listHolidays(CLINIC)).thenReturn(List.of());
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
        assertThat(model.getAttribute("weightsForm")).isNotNull();
        assertThat(model.getAttribute("employeeRoles")).isNotNull();
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
        Employee updated = employee("محمود");
        when(employeeService.update(eq(CLINIC), eq(updated.id()), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateEmployee(updated.id(), form("محمود", null), Validated.of(form("محمود", null)), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "employee.update", "employee");
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

        String view = controller.updateEmployee(UUID.randomUUID(), form("محمود", null), Validated.of(form("محمود", null)), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void updateEmployeeBlankNameIsRejectedBeforeService() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        Employee updated = employee("محمود");
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateEmployee(employeeId, form("   ", null), Validated.of(form("   ", null)), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("اسم الموظف مطلوب");
        verify(employeeService, never()).update(any(), any(), any());
    }

    @Test
    void archiveEmployeeLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        Employee archived = employee("محمود");
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
        assertThat(model.getAttribute("toastMessage")).isEqualTo("الموظف غير موجود");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(activityLogService, never()).log(any(), any(), any(), any());
    }

    @Test
    void archiveEmployeeSuspendsLinkedUser() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UserSummary linked = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", null, "active", "assistant",
                UUID.randomUUID(), employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(linked));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.archiveEmployee(employeeId, session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(userAdminService).suspend(CLINIC, linked.id(), MEMBERSHIP);
    }

    @Test
    void archiveEmployeeSkipsSuspendingOwnerLinkedUser() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UserSummary owner = new UserSummary(UUID.randomUUID(), "owner", "المالك", null, "active", "owner",
                UUID.randomUUID(), employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(owner));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.archiveEmployee(employeeId, session, model);

        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(userAdminService, never()).suspend(any(), any(), any());
    }

    @Test
    void archiveEmployeeSkipsSuspendingSelfLinkedUser() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UserSummary self = new UserSummary(UUID.randomUUID(), "me", "أنا", null, "active", "manager",
                MEMBERSHIP, employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(self));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.archiveEmployee(employeeId, session, model);

        assertThat(model.getAttribute("toastType")).isEqualTo("success");
        verify(userAdminService, never()).suspend(any(), any(), any());
    }

    @Test
    void updateEmployeeManagerCannotEditPeerManager() {
        HttpSession session = session("manager");
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UserSummary peer = new UserSummary(UUID.randomUUID(), "sara", "سارة", null, "active", "manager",
                UUID.randomUUID(), employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(peer));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.updateEmployee(employeeId, form("سارة", null), Validated.of(form("سارة", null)), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(employeeService, never()).update(any(), any(), any());
    }

    @Test
    void updateEmployeeManagerCanEditAssistant() {
        HttpSession session = session("manager");
        allowDashboard();
        Employee updated = employee("محمود");
        UserSummary assistant = new UserSummary(UUID.randomUUID(), "mahmoud", "محمود", null, "active", "assistant",
                UUID.randomUUID(), updated.id());
        when(userAdminService.list(CLINIC)).thenReturn(List.of(assistant));
        when(employeeService.update(eq(CLINIC), eq(updated.id()), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateEmployee(updated.id(), form("محمود", null), Validated.of(form("محمود", null)), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void archiveEmployeeManagerCannotArchivePeerManager() {
        HttpSession session = session("manager");
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UserSummary peer = new UserSummary(UUID.randomUUID(), "sara", "سارة", null, "active", "manager",
                UUID.randomUUID(), employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(peer));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.archiveEmployee(employeeId, session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(employeeService, never()).archive(any(), any());
    }

    @Test
    void renderCardHidesPeersAndSuperiorsFromManager() {
        HttpSession session = session("manager");
        allowDashboard();
        Employee peerEmployee = employee("سارة");
        Employee ownerEmployee = employee("المالك");
        Employee assistantEmployee = employee("أحمد");
        when(employeeService.list(CLINIC)).thenReturn(List.of(peerEmployee, ownerEmployee, assistantEmployee));
        when(userAdminService.list(CLINIC)).thenReturn(List.of(
                new UserSummary(UUID.randomUUID(), "sara", "سارة", null, "active", "manager", UUID.randomUUID(), peerEmployee.id()),
                new UserSummary(UUID.randomUUID(), "owner", "المالك", null, "active", "owner", UUID.randomUUID(), ownerEmployee.id()),
                new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", null, "active", "assistant", UUID.randomUUID(), assistantEmployee.id())));
        var settings = new ClinicSettingsService.ClinicSettings(
                java.time.LocalTime.of(9, 0), java.time.LocalTime.of(17, 0), 15, 26,
                new java.math.BigDecimal("20000"), 70, List.of(), List.of());
        when(clinicSettingsService.get(CLINIC)).thenReturn(settings);

        controller.settings(session, model);

        @SuppressWarnings("unchecked")
        var employees = (List<Employee>) model.getAttribute("employees");
        assertThat(employees).containsExactly(assistantEmployee);
    }

    @Test
    void archiveEmployeeReportsErrorWhenSuspendFails() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        Employee archived = employee("محمود");
        UserSummary linked = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", null, "active", "assistant",
                UUID.randomUUID(), employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(linked));
        when(employeeService.archive(CLINIC, employeeId)).thenReturn(archived);
        when(employeeService.list(CLINIC)).thenReturn(List.of());
        doThrow(new IllegalArgumentException("تعذر تعليق الحساب"))
                .when(userAdminService).suspend(eq(CLINIC), eq(linked.id()), eq(MEMBERSHIP));

        String view = controller.archiveEmployee(employeeId, session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "employee.archive", "employee");
    }

    @Test
    void updateEmployeeWithChangedRoleAssignsRole() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UserSummary summary = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", null, "active", "assistant", membershipId, employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(summary));
        Employee updated = new Employee(employeeId, "محمود", null, null, null, null, false, null, null);
        when(employeeService.update(eq(CLINIC), eq(employeeId), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        String view = controller.updateEmployee(employeeId, form("محمود", "manager"), Validated.of(form("محمود", "manager")), session, model);

        assertThat(view).isEqualTo("admin/employees :: employeesCard");
        verify(userAdminService).assignRole(CLINIC, membershipId, "manager", MEMBERSHIP);
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
        Employee updated = new Employee(employeeId, "المالك", null, null, null, null, false, null, null);
        when(employeeService.update(eq(CLINIC), eq(employeeId), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        controller.updateEmployee(employeeId, form("المالك", "manager"), Validated.of(form("المالك", "manager")), session, model);

        verify(userAdminService, never()).assignRole(any(), any(), any(), any());
    }

    @Test
    void updateEmployeeWithoutRoleSelectSkipsAssignRole() {
        HttpSession session = session();
        allowDashboard();
        Employee updated = employee("محمود");
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.update(eq(CLINIC), eq(updated.id()), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        controller.updateEmployee(updated.id(), form("محمود", null), Validated.of(form("محمود", null)), session, model);

        verify(userAdminService, never()).assignRole(any(), any(), any(), any());
    }

    @Test
    void updateEmployeeSameRoleSkipsAssignRole() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UserSummary summary = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", null, "active", "assistant", membershipId, employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(summary));
        Employee updated = new Employee(employeeId, "محمود", null, null, null, null, false, null, null);
        when(employeeService.update(eq(CLINIC), eq(employeeId), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        controller.updateEmployee(employeeId, form("محمود", "assistant"), Validated.of(form("محمود", "assistant")), session, model);

        verify(userAdminService, never()).assignRole(any(), any(), any(), any());
        assertThat(model.getAttribute("toastType")).isEqualTo("success");
    }

    @Test
    void updateEmployeeRoleChangeFailureShowsErrorAndStillLogsUpdate() {
        HttpSession session = session();
        allowDashboard();
        UUID employeeId = UUID.randomUUID();
        UUID membershipId = UUID.randomUUID();
        UserSummary summary = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", null, "active", "assistant", membershipId, employeeId);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(summary));
        doThrow(new IllegalArgumentException("الدور غير موجود: manager"))
                .when(userAdminService).assignRole(eq(CLINIC), eq(membershipId), eq("manager"), eq(MEMBERSHIP));
        Employee updated = new Employee(employeeId, "محمود", null, null, null, null, false, null, null);
        when(employeeService.update(eq(CLINIC), eq(employeeId), any(EmployeeRequest.class))).thenReturn(updated);
        when(employeeService.list(CLINIC)).thenReturn(List.of(updated));

        controller.updateEmployee(employeeId, form("محمود", "manager"), Validated.of(form("محمود", "manager")), session, model);

        assertThat(model.getAttribute("toastType")).isEqualTo("error");
        assertThat(((String) model.getAttribute("toastMessage"))).contains("الدور غير موجود");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "employee.update", "employee");
    }

    private static AdminController.EmployeeForm form(String name, String roleCode) {
        return AdminController.EmployeeForm.of(name, roleCode, "5200", "2000", false, "", "");
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
        return session("owner");
    }

    private static HttpSession session(String roleCode) {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        when(session.getAttribute(SessionKeys.ROLE_CODE)).thenReturn(roleCode);
        return session;
    }

    private static Employee employee(String name) {
        return new Employee(UUID.randomUUID(), name, null, null, null, null, false, null, null);
    }
}