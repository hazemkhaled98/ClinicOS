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
import com.clinicos.identity.api.UserAdminService;
import com.clinicos.identity.api.UserAdminService.UserCreateRequest;
import com.clinicos.identity.api.UserAdminService.UserSummary;
import com.clinicos.shared.ActivityLogService;
import com.clinicos.staff.api.EmployeeService;
import com.clinicos.ui.nav.NavSectionResolver;

import jakarta.servlet.http.HttpSession;

class UserAdminControllerTest {

    private static final UUID CLINIC = UUID.randomUUID();
    private static final UUID MEMBERSHIP = UUID.randomUUID();

    private LayoutModel layoutModel;
    private UserAdminService userAdminService;
    private EmployeeService employeeService;
    private ActivityLogService activityLogService;
    private UserAdminController controller;
    private Model model;

    @BeforeEach
    void setUp() {
        layoutModel = mock(LayoutModel.class);
        userAdminService = mock(UserAdminService.class);
        employeeService = mock(EmployeeService.class);
        activityLogService = mock(ActivityLogService.class);
        controller = new UserAdminController(layoutModel, userAdminService, employeeService, activityLogService);
        model = new ExtendedModelMap();
    }

    @Test
    void usersRendersPageForAdmin() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.users(session, model);

        assertThat(view).isEqualTo("admin/users-page");
        assertThat(model.getAttribute("users")).isEqualTo(List.of());
        assertThat(model.getAttribute("employees")).isEqualTo(List.of());
    }

    @Test
    void usersRedirectsHomeWithoutPermission() {
        HttpSession session = session();
        denyDashboard();

        String view = controller.users(session, model);

        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    void createUserLogsActivityAndReturnsCard() {
        HttpSession session = session();
        allowDashboard();
        UserSummary created = new UserSummary(UUID.randomUUID(), "ahmed", "أحمد", "a@b.com", "active", "owner", UUID.randomUUID());
        when(userAdminService.create(eq(CLINIC), any(UserCreateRequest.class))).thenReturn(created);
        when(userAdminService.list(CLINIC)).thenReturn(List.of(created));
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(
                new UserAdminController.UserForm("ahmed", "أحمد", "a@b.com", "hash123"), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.create", "user");
        assertThat(model.getAttribute("userErrorScope")).isNull();
    }

    @Test
    void createUserValidatesEmptyUsername() {
        HttpSession session = session();
        allowDashboard();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        String view = controller.createUser(
                new UserAdminController.UserForm("", "أحمد", "a@b.com", "hash123"), session, model);

        assertThat(view).isEqualTo("admin/users :: usersCard");
        assertThat(model.getAttribute("userErrors")).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) model.getAttribute("userErrors")).containsKey("username")).isTrue();
        verify(userAdminService, never()).create(any(), any());
    }

    @Test
    void suspendLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.suspend(userId, session, model);

        verify(userAdminService).suspend(CLINIC, userId);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.suspend", "user");
    }

    @Test
    void reactivateLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        UUID userId = UUID.randomUUID();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.reactivate(userId, session, model);

        verify(userAdminService).reactivate(CLINIC, userId);
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.reactivate", "user");
    }

    @Test
    void assignRoleLogsActivity() {
        HttpSession session = session();
        allowDashboard();
        UUID membershipId = UUID.randomUUID();
        when(userAdminService.list(CLINIC)).thenReturn(List.of());
        when(employeeService.list(CLINIC)).thenReturn(List.of());

        controller.assignRole(UUID.randomUUID(), "manager", membershipId, session, model);

        verify(userAdminService).assignRole(CLINIC, membershipId, "manager");
        verify(activityLogService).log(CLINIC, MEMBERSHIP, "user.assign_role", "user");
    }

    private void allowDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(
                        List.of(NavSectionResolver.sectionByRoute("admin-dashboard")),
                        "أحمد", "المالك", "19 مايو 2026", "admin-dashboard"));
    }

    private void denyDashboard() {
        when(layoutModel.forRequest(any(HttpSession.class), eq("admin-dashboard")))
                .thenReturn(new LayoutModel.LayoutData(List.of(), "أحمد", "مدير", "19 مايو 2026", "admin-dashboard"));
    }

    private static HttpSession session() {
        HttpSession session = mock(HttpSession.class);
        when(session.getAttribute(SessionKeys.CLINIC_ID)).thenReturn(CLINIC);
        when(session.getAttribute(SessionKeys.MEMBERSHIP_ID)).thenReturn(MEMBERSHIP);
        return session;
    }
}
